package com.forgepilot.review;

/**
 * A Review's execution status, which is orthogonal to its {@link ReviewDecision}
 * (ARCHITECTURE.md 3.2).
 *
 * <p>There is deliberately no {@code INVALIDATED}: execution state and semantic
 * validity are two different dimensions (3.5). Whether a Review still applies to
 * the pull request's current inputs is derived by comparing its four identity
 * columns against the pull request, never stored.
 */
public enum ReviewStatus {

    /** Persisted inside the SCM transaction; no worker has claimed it yet. */
    PENDING,

    /** Claimed. The claimant holds the attempt, token and lease that fence it. */
    RUNNING,

    /** Terminal. Never re-run and never overwritten. */
    COMPLETED,

    /**
     * The AI call failed, or its structure could not be repaired. A manual retry
     * returns this row to {@link #PENDING} with a new attempt — the row is reused
     * rather than replaced, so the history of attempts stays on one identity.
     */
    FAILED
}
