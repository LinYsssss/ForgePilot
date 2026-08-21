package com.forgepilot.scm;

/**
 * Published inside the transaction that updated the pull request row.
 *
 * <p>A synchronous {@code @EventListener} joins that transaction, so if the
 * listener fails the pull request update rolls back with it: there must be no
 * committed state in which the pull request moved but the Review it implies is
 * missing. {@code @TransactionalEventListener} is the wrong tool here and is
 * forbidden — its default phase runs after commit, where nothing can be rolled
 * back any more.
 *
 * <p>The type lives in {@code scm} and {@code review} will import it. That keeps
 * the dependency pointing the way ARCHITECTURE.md 1.3 allows and leaves {@code scm}
 * with no compile time dependency on {@code review}. In batch 2 there is no
 * listener at all, so the publication is only visible to a test-scoped listener.
 */
public record PullRequestChanged(Long pullRequestId, String headSha, String reviewInputFingerprint) {
}
