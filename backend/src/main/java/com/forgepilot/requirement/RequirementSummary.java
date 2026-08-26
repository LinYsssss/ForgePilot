package com.forgepilot.requirement;

import java.time.Instant;

/**
 * 列表中的一条需求。标题取自当前修订，因为 {@code requirement} 行本身不携带
 * 任何文本：列表在服务端拼装，而不是丢给客户端去拼。
 */
public record RequirementSummary(long id, String title, RequirementStatus status, Long assigneeId,
        String assigneeUsername, int currentRevisionSeq, Instant updatedAt) {

    static RequirementSummary of(Requirement requirement, String assigneeUsername) {
        RequirementRevision current = requirement.getCurrentRevision();
        return new RequirementSummary(requirement.getId(), current.getTitle(), requirement.getStatus(),
                requirement.getAssigneeId(), assigneeUsername, current.getSeq(),
                requirement.getUpdatedAt());
    }
}
