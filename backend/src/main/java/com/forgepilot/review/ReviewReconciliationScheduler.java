package com.forgepilot.review;

import com.forgepilot.review.ReviewClaimRepository.StalledReview;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Puts back on the path Reviews that are stored but not moving: PENDING rows
 * nobody claimed, and RUNNING rows whose lease expired.
 *
 * <p>This is not a fallback branch. Measured, an exception in an after-commit
 * callback — including the {@code TaskRejectedException} of a full queue — is
 * caught and logged by Spring and never reaches the caller: the webhook still
 * answers 202 and the PENDING row is already committed. Without something that
 * picks those rows up, PENDING is a state with an in-edge and no out-edge, so
 * this is not "recover if something went wrong", it is the only owner of that
 * state. ARCHITECTURE.md 3.1, 3.2 and D008 all name it.
 *
 * <p><strong>The rule that keeps it honest is structural: the recovery query's
 * FROM clause contains {@code review} and nothing else</strong>
 * ({@link ReviewClaimRepository#findStalled}). A result set driven by
 * {@code review} can only ever be rows that already exist, so no condition,
 * however wrong, can turn this into the backfill 3.1 forbids. That rule is
 * checkable at a glance in code review, which "remember not to backfill" is not.
 *
 * <p>It also changes nothing else: the rows it finds go back through the same
 * claim and the same executor, gaining an attempt that shows on the row. There is
 * no retry counter, no alternative path and no invented data.
 */
@Component
public class ReviewReconciliationScheduler {

    private final ReviewClaimRepository claims;
    private final ReviewExecutor executor;
    private final int pendingStallSeconds;
    private final int batchLimit;

    ReviewReconciliationScheduler(ReviewClaimRepository claims, ReviewExecutor executor,
            @Value("${forgepilot.review.pending-stall-seconds}") int pendingStallSeconds,
            @Value("${forgepilot.review.queue-capacity}") int queueCapacity) {
        this.claims = claims;
        this.executor = executor;
        this.pendingStallSeconds = pendingStallSeconds;
        // One pass never offers more than the queue can hold. Beyond that the pool
        // would reject the surplus, which costs a scan and gains nothing: those
        // rows are still stalled on the next pass.
        this.batchLimit = queueCapacity;
    }

    /**
     * Deliberately not transactional. Each claim opens and commits its own short
     * transaction, which keeps every one of them evaluating {@code now()} against a
     * fresh reference time — inside one long transaction the later rows in a batch
     * would be compared against an increasingly stale clock and simply stop being
     * recovered.
     *
     * <p>The first pass waits a full interval so that a Review handed to the
     * executor during startup is not treated as stalled before it has had a chance
     * to run.
     */
    @Scheduled(fixedDelayString = "${forgepilot.review.reconciliation-interval-ms}",
            initialDelayString = "${forgepilot.review.reconciliation-interval-ms}")
    public void recover() {
        for (StalledReview stalled : claims.findStalled(pendingStallSeconds, batchLimit)) {
            executor.submit(stalled.getProjectId(), stalled.getReviewId());
        }
    }
}
