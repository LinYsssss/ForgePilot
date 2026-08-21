package com.forgepilot.requirement;

import java.time.Instant;

/**
 * A requirement in a list. The title comes from the current revision, because
 * the {@code requirement} row carries no prose (D011): the list is assembled
 * here rather than stitched together in the client.
 */
public record RequirementSummary(long id, String title, RequirementStatus status, Long assigneeId,
        String assigneeUsername, int currentRevisionSeq, Instant updatedAt, String reviewActivity) {

    static RequirementSummary of(Requirement requirement, String assigneeUsername) {
        RequirementRevision current = requirement.getCurrentRevision();
        return new RequirementSummary(requirement.getId(), current.getTitle(), requirement.getStatus(),
                requirement.getAssigneeId(), assigneeUsername, current.getSeq(),
                requirement.getUpdatedAt(), RequirementDetail.NO_PULL_REQUEST);
    }
}
