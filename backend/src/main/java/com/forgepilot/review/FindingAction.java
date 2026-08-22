package com.forgepilot.review;

/**
 * What the actor meant to do, recorded alongside the mechanical from/to pair.
 *
 * <p>Both are stored because a transition read back later should not require
 * re-deriving intent from a status pair — {@code FIXED -> IN_PROGRESS} is a
 * reviewer sending work back, and that is worth saying rather than inferring.
 *
 * <p>Each action maps to exactly one legal transition of {@link FindingStatus},
 * so the two can never disagree.
 */
public enum FindingAction {

    /** OPEN -> CONFIRMED. */
    CONFIRM,

    /** OPEN -> REJECTED, or CONFIRMED -> REJECTED. */
    REJECT,

    /** CONFIRMED -> IN_PROGRESS. The developer takes it; nobody assigns it to them. */
    CLAIM,

    /** IN_PROGRESS -> FIXED. */
    MARK_FIXED,

    /** FIXED -> VERIFIED. */
    VERIFY,

    /** FIXED -> IN_PROGRESS. Re-verification failed. */
    SEND_BACK,

    /** VERIFIED -> CLOSED. */
    CLOSE,

    /** REJECTED -> OPEN, and only for an inherited suppression. */
    REOPEN
}
