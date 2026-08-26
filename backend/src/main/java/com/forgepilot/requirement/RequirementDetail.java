package com.forgepilot.requirement;

import java.time.Instant;

/** API 对外呈现的需求详情，带上它的当前修订。 */
public record RequirementDetail(long id, RequirementStatus status, Long assigneeId, String assigneeUsername,
        Instant createdAt, Instant updatedAt, RevisionView currentRevision) {

    /**
     * 审查活动状态**刻意不在这里**。它要由 {@code pull_request} 与 {@code review}
     * 共同推导，而 ARCHITECTURE.md 1.3 规定的依赖方向恰好相反——{@code review}
     * 可以读本模块，反之绝不可以。在这里算它，就需要 {@code requirement} 反向
     * 伸进 {@code review}，那会让功能依赖图成环，ArchUnit 会直接拒绝。
     *
     * <p>它改由 {@code review} 通过自己的只读端点提供，客户端单独去取。
     * 这里曾放过一个常量 {@code "NO_PR"} 作占位；替换掉的是
     * 那个占位符，而不是这条边界。
     */
    static RequirementDetail of(Requirement requirement, String assigneeUsername, RevisionView currentRevision) {
        return new RequirementDetail(requirement.getId(), requirement.getStatus(), requirement.getAssigneeId(),
                assigneeUsername, requirement.getCreatedAt(), requirement.getUpdatedAt(), currentRevision);
    }
}
