package com.forgepilot.requirement;

import com.forgepilot.project.ProjectMemberRemoving;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 成员离开项目时释放它在需求上的指派。
 *
 * <p>指派是一条**活权限**，不是既成事实：人走了，这条需求就该回到无人负责，而不是
 * 继续指向一个已经不是成员的人。{@code requirement.assignee_id} 的复合外键没有
 * {@code ON DELETE}，所以不置空的话删除会被数据库直接拒绝。
 *
 * <p>同步监听，加入发布方的事务；见 {@link ProjectMemberRemoving} 的说明。
 */
@Component
class RemovedMemberAssignmentListener {

    private final RequirementRepository requirements;

    RemovedMemberAssignmentListener(RequirementRepository requirements) {
        this.requirements = requirements;
    }

    @EventListener
    void releaseAssignmentsInTheSameTransaction(ProjectMemberRemoving event) {
        event.revoked("requirement assignments",
                requirements.clearAssignee(event.projectId(), event.userId()));
    }
}
