package com.forgepilot.requirement;

import java.time.Instant;

/** A requirement with its current revision, as the API shows it. */
public record RequirementDetail(long id, RequirementStatus status, Long assigneeId, String assigneeUsername,
        Instant createdAt, Instant updatedAt, RevisionView currentRevision) {

    /**
     * Review activity is deliberately absent here. It is derived from both
     * {@code pull_request} and {@code review}, and ARCHITECTURE.md 1.3 runs the
     * dependency the other way — {@code review} may read this module, never the
     * reverse. Computing it here would need {@code requirement} to reach into
     * {@code review}, which cycles the feature graph and ArchUnit rejects.
     *
     * <p>It is served by {@code review} instead, on its own read endpoint, so the
     * client asks for it separately. Batch 1 carried a constant {@code "NO_PR"}
     * here as a placeholder; batch 3 replaced the placeholder rather than the
     * boundary.
     */
    static RequirementDetail of(Requirement requirement, String assigneeUsername, RevisionView currentRevision) {
        return new RequirementDetail(requirement.getId(), requirement.getStatus(), requirement.getAssigneeId(),
                assigneeUsername, requirement.getCreatedAt(), requirement.getUpdatedAt(), currentRevision);
    }
}
