package com.forgepilot.scm;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.project.ProjectRole;
import com.forgepilot.requirement.RequirementDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对 PR 与需求关联关系的人工纠正（PRD P1）。
 *
 * <p>自动的 {@code REQ-<n>} 关联只是入库时做的一次猜测，D007 把最终话语权
 * 交给了页面。因此每次纠正都会<em>在同一个事务里</em>写下一行
 * {@code pull_request_requirement_event}：关联变了却没有审计行，
 * 或有审计行却描述了一次并未提交的变更，两者都比完全没有审计更糟。
 *
 * <p>只有 LEADER 能走到这里。PRD P1 同时允许 DEVELOPER 在“当前 head 尚无
 * 最终人工 Decision 时”纠正自己的 PR，而那一半刻意没有实现：本批次里
 * {@code review} 还不存在，“没有最终决策”无从判定，而一个永远回答
 * “不存在决策”的检查会静默地授予比 P1 更多的权限。
 */
@Service
class PullRequestAssociationService {

    private final PullRequestRepository pullRequests;
    private final PullRequestRequirementEventRepository events;
    private final ProjectAccessService access;
    private final RequirementDirectory requirements;

    PullRequestAssociationService(PullRequestRepository pullRequests,
            PullRequestRequirementEventRepository events, ProjectAccessService access,
            RequirementDirectory requirements) {
        this.pullRequests = pullRequests;
        this.events = events;
        this.access = access;
        this.requirements = requirements;
    }

    /**
     * 把该 PR 指向 {@code requirementId}；传 null 则指向「无」——清除关联同样是
     * 一次合法纠正，并且与两条需求之间的迁移一样要被审计。
     *
     * <p>把关联设成它已有的值，会由 {@code ck_pr_requirement_event_is_a_change}
     * 拒绝，而不是在这里预先检查：审计表只记录**变化**，一个产生不了审计行的
     * 请求就不是一次纠正。那次冲突会让整个事务回滚——这正是要点：
     * 无论如何都不会有任何东西被写入（D013.11）。
     */
    @Transactional
    PullRequestResponse correct(long projectId, long actorId, long pullRequestId, Long requirementId,
            String reason) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        PullRequest pullRequest = pullRequests.findWithLockByProjectIdAndId(projectId, pullRequestId)
                .orElseThrow(ApiException::notFound);
        // 经 requirement 的只读 facade 解析，并按本 PR 自己的项目过滤
        // （D015.6、D013.2）。复合外键同样会拒绝外项目的 id，但只会以
        // 「插入失败 + 一条点名约束的报错」的形式；先问一句，就把它变成了
        // 调用方能据以行动的答案。在这里，来自别的项目的 id 与从未签发过的 id
        // 不可区分，因此都不会泄露另一个项目的内容。
        if (requirementId != null && !requirements.existsInProject(projectId, requirementId)) {
            throw ApiException.unprocessable("That requirement does not belong to this project.");
        }

        Long previous = pullRequest.getRequirementId();
        pullRequest.linkRequirement(requirementId);
        events.save(PullRequestRequirementEvent.userCorrection(projectId, pullRequest.getId(),
                previous, requirementId, actorId, reason));
        return PullRequestResponse.of(pullRequest);
    }
}
