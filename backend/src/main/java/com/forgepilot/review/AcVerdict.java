package com.forgepilot.review;

/**
 * What a Review concluded about one acceptance criterion (ARCHITECTURE.md 3.5).
 *
 * <p>Every AC of the reviewed revision ends with exactly one of these. There is
 * deliberately no "no verdict" value: an AC the model said nothing about is
 * filled in as {@link #NOT_FOUND} by {@link ReviewOutputValidator}, because
 * "silent" and "nothing implements this" must not be the same thing on a page a
 * human uses to decide whether a pull request satisfies a requirement.
 *
 * <p>Batches never produce these values. A batch sees part of the diff, so a
 * verdict from one batch would contradict another's; only the final synthesis,
 * which sees every batch's evidence, may conclude (D002).
 */
public enum AcVerdict {

    /** The diff implements this criterion, with evidence inside the changed files. */
    COVERED,

    /** Nothing in the reviewed part of the diff implements it. Also the fill-in for a silent model. */
    NOT_FOUND,

    /** Something addresses it, but the evidence is partial or contradicted. */
    AT_RISK
}
