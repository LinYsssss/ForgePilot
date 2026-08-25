package com.forgepilot.scm;

import java.util.List;

import com.forgepilot.project.ProjectMemberRemoving;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 成员离开项目时删除它在本项目的 SCM 身份绑定。
 *
 * <p>绑定是**项目作用域的成员属性**，随成员关系消亡；用户自有的
 * {@link ScmIdentity} 与平台账号完全不受影响——身份归用户本人，不是项目财产。
 *
 * <p>{@code pull_request} 的两列不可变作者快照同样不动。只有可重算映射
 * {@code author_user_id} 会被置空，而那件事由全库唯一那条列级
 * {@code ON DELETE SET NULL} 自己完成（D010），不需要本监听器插手。
 *
 * <p>同步监听，加入发布方的事务；见 {@link ProjectMemberRemoving} 的说明。
 */
@Component
class RemovedMemberBindingListener {

    private final ProjectMemberScmBindingRepository bindings;

    RemovedMemberBindingListener(ProjectMemberScmBindingRepository bindings) {
        this.bindings = bindings;
    }

    @EventListener
    void revokeProjectBindingsInTheSameTransaction(ProjectMemberRemoving event) {
        List<ProjectMemberScmBinding> revoked = bindings.findByProjectIdAndUserIdOrderByIdAsc(
                event.projectId(), event.userId());
        bindings.deleteAll(revoked);
        bindings.flush();
        event.revoked("scm bindings", revoked.size());
    }
}
