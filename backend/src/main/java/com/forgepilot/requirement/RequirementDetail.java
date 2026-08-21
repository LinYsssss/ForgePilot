package com.forgepilot.requirement;

import java.time.Instant;

/** A requirement with its current revision, as the API shows it. */
public record RequirementDetail(long id, RequirementStatus status, Long assigneeId, String assigneeUsername,
        Instant createdAt, Instant updatedAt, String reviewActivity, RevisionView currentRevision) {

    /**
     * Batch 1 has no pull request at all, so the review activity of every
     * requirement is this constant. It is derived, never stored and never
     * writable (api-contract 3).
     */
    static final String NO_PULL_REQUEST = "NO_PR";

    static RequirementDetail of(Requirement requirement, String assigneeUsername, RevisionView currentRevision) {
        return new RequirementDetail(requirement.getId(), requirement.getStatus(), requirement.getAssigneeId(),
                assigneeUsername, requirement.getCreatedAt(), requirement.getUpdatedAt(),
                NO_PULL_REQUEST, currentRevision);
    }
}
