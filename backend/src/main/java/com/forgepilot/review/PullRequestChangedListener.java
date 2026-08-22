package com.forgepilot.review;

import com.forgepilot.review.ReviewService.ReviewReady;
import com.forgepilot.scm.PullRequestChanged;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The two halves of the automatic trigger. They are two methods, and that is not
 * a style choice: measured, a single method carrying both {@code @EventListener}
 * and {@code @TransactionalEventListener(AFTER_COMMIT)} is invoked <em>once</em>
 * and after the commit. The transactional factory wins, the plain adapter is
 * never created, and the container neither warns nor fails — so the in-transaction
 * half disappears while every single-threaded test stays green. What disappears
 * with it is the guarantee that a failing listener rolls the SCM transaction back.
 */
@Component
public class PullRequestChangedListener {

    private final ReviewService reviews;
    private final ReviewExecutor executor;

    PullRequestChangedListener(ReviewService reviews, ReviewExecutor executor) {
        this.reviews = reviews;
        this.executor = executor;
    }

    /**
     * Joins the transaction that updated the pull request and idempotently creates
     * or takes the Review for its current four-tuple.
     *
     * <p>Throwing here is meant to be fatal to the whole ingestion: there must be
     * no committed state in which the pull request moved and the Review it implies
     * is missing (ARCHITECTURE.md 3.1). Nothing is caught for that reason.
     */
    @EventListener
    public void openTheReviewInTheSameTransaction(PullRequestChanged event) {
        reviews.openForDelivery(event.pullRequestId());
    }

    /**
     * Hands the committed row to the executor, and does nothing else.
     *
     * <p>Nothing in this method may touch the database. Measured, at this point
     * {@code isActualTransactionActive()} still answers true and
     * {@code isConnectionTransactional()} still answers true, while the physical
     * connection's autoCommit has already been restored — so
     * {@code EntityManager.persist} fails outright with "No active transaction"
     * (which is the good outcome) and a bare {@code JdbcTemplate.update} quietly
     * <em>succeeds</em>, committing a single statement on a connection nothing can
     * roll back. That last one is the dangerous case precisely because it looks
     * like it works. If a write is ever needed here it must be
     * {@code REQUIRES_NEW}, and the phase must never be decided by asking
     * {@code TransactionSynchronizationManager} — measured, it answers wrongly.
     *
     * <p>Nothing here may block either: the webhook's 202 waits for this method to
     * return, measured almost exactly one-for-one with however long it takes.
     *
     * <p>{@link ReviewReady} rather than {@code PullRequestChanged} so that this
     * needs no lookup to know which row to run — and so that the human trigger and
     * the retry, which publish the same signal inside their own transaction, reach
     * the executor by this one path instead of a second one.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handOffOnceTheRowIsCommitted(ReviewReady event) {
        executor.submit(event.projectId(), event.reviewId());
    }
}
