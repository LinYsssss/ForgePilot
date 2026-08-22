package com.forgepilot.review;

import java.util.Optional;
import java.util.UUID;

import com.forgepilot.common.ApiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The worker side of a Review: taking one, holding it, and finishing it.
 *
 * <p>Every write here is conditional on {@code review_id + execution_token +
 * status = RUNNING}, which is what ARCHITECTURE.md 3.2 means by "an expired
 * worker's writes affect zero rows". All four of its writes are fenced, not just
 * the obvious one — measured, an unfenced {@code fail} lets a dead attempt mark
 * the attempt that replaced it as failed, and an unfenced Finding insert lets a
 * dead attempt's output appear as this round's result. The fourth is fenced by
 * the database rather than by code, through {@code finding}'s composite foreign
 * key onto {@code (project_id, id, execution_attempt)}: checking the attempt in
 * Java first has a measured window in which the check passes and the insert then
 * lands under a live Review.
 *
 * <p>The analysis itself belongs to {@link ReviewPipeline}; this class takes the
 * Review, holds it and finishes it. The split is also what keeps the dependency
 * one-way: the pipeline never calls back into the executor, so there is no bean
 * cycle to break with a lazy proxy, and the state machine stays readable in one
 * method.
 */
@Service
public class ReviewExecutor {

    /** What a worker holds while it owns a Review. All four fields are the fence. */
    public record Claim(long projectId, long reviewId, int attempt, UUID token) {
    }

    private final ThreadPoolTaskExecutor pool;
    private final ReviewClaimRepository claims;
    private final ReviewPipeline pipeline;
    private final TransactionTemplate ownTransaction;
    private final TransactionTemplate finishingTransaction;
    private final int leaseSeconds;

    ReviewExecutor(@Qualifier("reviewWorkerPool") ThreadPoolTaskExecutor pool, ReviewClaimRepository claims,
            ReviewPipeline pipeline, PlatformTransactionManager transactions,
            @Value("${forgepilot.review.lease-seconds}") int leaseSeconds) {
        this.pool = pool;
        this.claims = claims;
        this.pipeline = pipeline;
        // Claiming and renewing get their own transaction, because now() is frozen
        // at transaction start: a lease written inside a long transaction is short
        // by however long that transaction has already been running, and a renewal
        // that is short is a renewal that can be preempted. They touch no key
        // column of review, so a nested one cannot block on its own caller.
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Completing and failing join the caller instead. A Review's findings and
        // its terminal status must commit together (design.md 4.4) — a separate
        // transaction here would allow a report to exist without the state that
        // says it is finished, or the reverse.
        this.finishingTransaction = new TransactionTemplate(transactions);
        this.leaseSeconds = leaseSeconds;
    }

    /**
     * Hands one stored Review to the pool. This is the only way work starts, and it
     * is called from exactly two places: after the transaction that created the
     * PENDING row has committed, and from reconciliation.
     */
    public void submit(long projectId, long reviewId) {
        pool.execute(() -> run(projectId, reviewId));
    }

    /**
     * Takes the Review if it is takeable, discarding the abandoned attempt's output
     * in the same transaction first. The two statements cannot be split: the
     * findings of the attempt being abandoned reference the attempt number that is
     * about to change, so deleting them is a precondition of the claim rather than
     * a tidy-up after it.
     *
     * <p>An empty result is the normal answer, not an error. It means somebody else
     * holds a live lease, or the row is COMPLETED, or it was already claimed while
     * this call was queued.
     */
    public Optional<Claim> claim(long projectId, long reviewId) {
        return ownTransaction.execute(status -> {
            claims.discardAbandonedFindings(projectId, reviewId);
            UUID token = UUID.randomUUID();
            if (claims.claim(projectId, reviewId, token.toString(), leaseSeconds) != 1) {
                return Optional.empty();
            }
            // Safe to read back rather than RETURNING: the update holds this row's
            // lock until commit, so nothing can move the attempt in between.
            return Optional.of(new Claim(projectId, reviewId,
                    claims.attemptOf(projectId, reviewId), token));
        });
    }

    /** False means the lease was lost; the caller no longer owns this Review. */
    public boolean renew(Claim claim) {
        return Boolean.TRUE.equals(ownTransaction.execute(status ->
                claims.renew(claim.projectId(), claim.reviewId(), claim.token().toString(), leaseSeconds) == 1));
    }

    public boolean complete(Claim claim) {
        return Boolean.TRUE.equals(finishingTransaction.execute(status ->
                claims.complete(claim.projectId(), claim.reviewId(), claim.token().toString()) == 1));
    }

    public boolean fail(Claim claim) {
        return Boolean.TRUE.equals(finishingTransaction.execute(status ->
                claims.fail(claim.projectId(), claim.reviewId(), claim.token().toString()) == 1));
    }

    /**
     * The pool task, and the whole execution state machine of 3.2 in one place:
     * claim it, analyse it, then either fail it or store its report and complete
     * it.
     *
     * <p>It runs on a pool thread with no transaction of its own, which is what
     * lets {@link #claim} be a short transaction that starts and commits inside one
     * Review rather than one batch, and what keeps the provider calls — up to 120 s
     * each — off the five-connection pool entirely.
     *
     * <p>Not claiming is the normal answer for a Review somebody else already
     * holds, so it does nothing and says nothing.
     */
    void run(long projectId, long reviewId) {
        Optional<Claim> taken = claim(projectId, reviewId);
        if (taken.isEmpty()) {
            return;
        }
        Claim claim = taken.get();

        Optional<ReviewPipeline.Report> report;
        try {
            report = pipeline.analyse(claim, () -> renew(claim));
        } catch (ApiException providerFailure) {
            // 3.2: "RUNNING -> FAILED: AI 失败". Letting this escape would leave the
            // row RUNNING until its lease expired, and reconciliation would then
            // reclaim it and call the same unreachable provider again, forever.
            // FAILED stops and waits for a person, which is what 3.2's manual retry
            // is for.
            fail(claim);
            return;
        }
        if (report.isEmpty()) {
            // A batch that could not be parsed even after its one repair, or a
            // synthesis that failed validation. Nothing is written: 3.4.4 forbids a
            // partial report and 3.5 forbids a successful empty one.
            fail(claim);
            return;
        }

        finishingTransaction.executeWithoutResult(status -> {
            pipeline.store(claim, report.get());
            if (!complete(claim)) {
                // The lease was lost between the analysis and this write. The report
                // must not survive the Review it belongs to: this attempt no longer
                // owns the row, and another one is already producing its own.
                status.setRollbackOnly();
            }
        });
    }
}
