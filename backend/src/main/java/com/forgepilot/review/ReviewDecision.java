package com.forgepilot.review;

/**
 * The one-shot human verdict on a Review (ARCHITECTURE.md 3.1).
 *
 * <p>Only {@code PENDING -> APPROVE | REQUEST_CHANGES}, written once, never
 * overwritten, reversed or rewritten. The write locks the pull request row,
 * checks six preconditions and then updates conditionally on
 * {@code decision = 'PENDING'}; anything but one affected row is a conflict.
 *
 * <p>Once a head SHA carries a {@code REQUEST_CHANGES}, that head can never be
 * approved. Changing the base, the requirement association, the requirement
 * revision or re-syncing the diff does not lift it — only a new head SHA does.
 */
public enum ReviewDecision {

    PENDING,
    APPROVE,
    REQUEST_CHANGES
}
