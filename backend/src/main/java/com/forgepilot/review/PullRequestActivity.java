package com.forgepilot.review;

/**
 * The derived review activity of <strong>one pull request</strong> (PRD.md 5).
 *
 * <p>Six values, not eight. {@code NO_PR} and {@code MIXED} belong to
 * requirement-level aggregation and are meaningless here — DECISIONS.md D011
 * spells this domain out, and IMPLEMENTATION-PLAN.md's eight-value list is the
 * union of two levels rather than one level's domain. That is why this is a
 * separate enum from {@link RequirementActivity} instead of one shared enum:
 * a single eight-valued type would let {@code NO_PR} be returned for a pull
 * request, which cannot mean anything.
 *
 * <p>Never stored. It is computed from the pull request's current head,
 * fingerprint and requirement revision against the Review carrying that same
 * identity.
 */
public enum PullRequestActivity {

    /** No Review matches the pull request's current head, fingerprint and revision. */
    REVIEW_REQUIRED,

    /** The current Review's execution failed. */
    FAILED,

    /** The current Review's decision is REQUEST_CHANGES. */
    CHANGES_REQUESTED,

    /** The current Review is running, or finished and is waiting for a human. */
    REVIEWING,

    /** The current Review is queued and unclaimed. */
    PENDING,

    /** The current Review's decision is APPROVE. */
    APPROVED
}
