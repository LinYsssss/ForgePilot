package com.forgepilot.review;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * A Finding's human handling lifecycle (PRD.md 5).
 *
 * <p>This is <strong>orthogonal</strong> to {@link FindingContinuity}: one says
 * what a person decided, the other says where the finding came from across
 * rounds. PRD.md is explicit that they must not be merged into one field or one
 * UI label, so they are two enums and two columns.
 *
 * <p>{@code NOT_REPORTED} is deliberately absent. ARCHITECTURE.md 3.6 makes it a
 * query-derived observation about the previous round — it is never stored, and
 * "not reported this round" must never be read as "fixed".
 */
public enum FindingStatus {

    OPEN,
    CONFIRMED,
    IN_PROGRESS,
    FIXED,
    VERIFIED,
    CLOSED,
    REJECTED;

    /**
     * The transition table, encoded as data so the tests can assert it pair by
     * pair rather than restating it in prose (the shape batch 1 established for
     * {@code RequirementStatus}).
     *
     * <p>{@code REJECTED -> OPEN} is here, but reaching it takes more than a legal
     * transition: PRD.md 5 allows reopening <strong>only</strong> an inherited
     * suppression, so the service also requires {@code continuity = SUPPRESSED}.
     * An ordinary rejection is terminal, and that extra condition cannot be
     * expressed in this table.
     */
    private static final Map<FindingStatus, Set<FindingStatus>> ALLOWED_TARGETS = Map.of(
            OPEN, EnumSet.of(CONFIRMED, REJECTED),
            CONFIRMED, EnumSet.of(IN_PROGRESS, REJECTED),
            IN_PROGRESS, EnumSet.of(FIXED),
            FIXED, EnumSet.of(VERIFIED, IN_PROGRESS),
            VERIFIED, EnumSet.of(CLOSED),
            CLOSED, EnumSet.noneOf(FindingStatus.class),
            REJECTED, EnumSet.of(OPEN));

    public boolean canMoveTo(FindingStatus target) {
        return ALLOWED_TARGETS.get(this).contains(target);
    }
}
