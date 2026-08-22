package com.forgepilot.review;

/**
 * Where a Finding came from relative to the previous round on the same pull
 * request (D009, ARCHITECTURE.md 3.6).
 *
 * <p>Orthogonal to {@link FindingStatus}. A reopened suppression keeps
 * {@code SUPPRESSED} here — lineage is a fact about history, and it does not stop
 * being true because someone changed the current status (PRD.md 5).
 *
 * <p>Computation priority is fixed at {@code SUPPRESSED > PERSISTING > NEW}, and
 * continuity is only ever computed within one pull request.
 */
public enum FindingContinuity {

    /** No match in the previous COMPLETED review of this pull request. */
    NEW,

    /**
     * Matched the previous round by {@code finding_key}. Starts from
     * {@link FindingStatus#OPEN} again — a persisting finding is not a decided one.
     */
    PERSISTING,

    /**
     * The most recent human judgement on this {@code finding_key} in this pull
     * request was a rejection, and both {@code evidence_hash} and
     * {@code basis_hash} are unchanged. Only then may the rejection be inherited,
     * so a suppression cannot survive the code or the requirement moving under it.
     */
    SUPPRESSED
}
