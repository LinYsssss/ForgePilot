package com.forgepilot.review;

import com.forgepilot.project.ProjectMemberRemoving;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 成员离开项目时释放它认领的 Finding。
 *
 * <p>V6 的注释已经写明 {@code finding.assignee_id} 与
 * {@code finding_event.actor_id} 指向相反的方向且理由相反：认领是活权限，随成员关系
 * 消亡；而已经发生过的人工决定是事实，永久保留。所以这里只置空 assignee，一条
 * {@code finding_event} 都不动。
 *
 * <p>同步监听，加入发布方的事务；见 {@link ProjectMemberRemoving} 的说明。
 */
@Component
class RemovedMemberClaimListener {

    private final FindingRepository findings;

    RemovedMemberClaimListener(FindingRepository findings) {
        this.findings = findings;
    }

    @EventListener
    void releaseClaimsInTheSameTransaction(ProjectMemberRemoving event) {
        event.revoked("finding assignments",
                findings.clearAssignee(event.projectId(), event.userId()));
    }
}
