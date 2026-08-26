package com.forgepilot.scm;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.project.ProjectMember;
import com.forgepilot.project.ProjectRole;
import com.forgepilot.requirement.RequirementDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对 PR 与需求关联关系的人工纠正（PRD P1）。
 *
 * <p>自动的 {@code REQ-<n>} 关联只是入库时做的一次猜测，最终话语权
 * 在页面。因此每次纠正都会<em>在同一个事务里</em>写下一行
 * {@code pull_request_requirement_event}：关联变了却没有审计行，
 * 或有审计行却描述了一次并未提交的变更，两者都比完全没有审计更糟。
 *
 * <p>LEADER 始终可改。PR 作者在「当前 head 尚无任何人工终局 Decision」时也可改
 * （PRD P1）：判据由 {@link PullRequestDecisionGate} 回答——
 * 接口在本包、实现在 {@code review}，因此编译期依赖方向仍是 {@code review → scm}，
 * ArchUnit 规则 3 不被触碰。
 *
 * <p>「本人 PR」按项目级稳定外部 user id 判定，**绝不按用户名**：
 * 用户名可以被重新分配，按名字匹配会把别人的 PR 交给一个改过名的账号。
 * 没有已核验 SCM 身份的成员匹配不到任何东西——这是 fail-closed 的方向。
 * 注意闸门问的是「非 PENDING 的 Decision」而不是「有没有 Review」：
 * 自动投递本来就会先建出 PENDING Review，按后者写会让作者纠正路径实际不可达。
 */
@Service
class PullRequestAssociationService {

    private final PullRequestRepository pullRequests;
    private final PullRequestRequirementEventRepository events;
    private final ProjectAccessService access;
    private final RequirementDirectory requirements;
    private final PullRequestDecisionGate decisions;
    private final ProjectScmIdentityAccess scmIdentities;

    PullRequestAssociationService(PullRequestRepository pullRequests,
            PullRequestRequirementEventRepository events, ProjectAccessService access,
            RequirementDirectory requirements, PullRequestDecisionGate decisions,
            ProjectScmIdentityAccess scmIdentities) {
        this.pullRequests = pullRequests;
        this.events = events;
        this.access = access;
        this.requirements = requirements;
        this.decisions = decisions;
        this.scmIdentities = scmIdentities;
    }

    /**
     * 把该 PR 指向 {@code requirementId}；传 null 则指向「无」——清除关联同样是
     * 一次合法纠正，并且与两条需求之间的迁移一样要被审计。
     *
     * <p>把关联设成它已有的值，会由 {@code ck_pr_requirement_event_is_a_change}
     * 拒绝，而不是在这里预先检查：审计表只记录**变化**，一个产生不了审计行的
     * 请求就不是一次纠正。那次冲突会让整个事务回滚——这正是要点：
     * 无论如何都不会有任何东西被写入。
     */
    @Transactional
    PullRequestResponse correct(long projectId, long actorId, long pullRequestId, Long requirementId,
            String reason) {
        ProjectMember member = access.requireMember(projectId, actorId);
        PullRequest pullRequest = pullRequests.findWithLockByProjectIdAndId(projectId, pullRequestId)
                .orElseThrow(ApiException::notFound);
        authorize(projectId, member, pullRequest);
        // 经 requirement 的只读 facade 解析，并按本 PR 自己的项目过滤。
        // 复合外键同样会拒绝外项目的 id，但只会以
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
        return PullRequestResponse.of(pullRequest, true);
    }

    boolean canCorrect(long projectId, ProjectMember member, PullRequest pullRequest) {
        return member.hasRole(ProjectRole.LEADER)
                || isAuthor(projectId, member, pullRequest)
                        && !decisions.hasFinalDecisionOnHead(
                                projectId, pullRequest.getId(), pullRequest.getHeadSha());
    }

    /**
     * PRD P1 的两条授权路径。REVIEWER 一条都不占：他能对 Review 下终局裁定，
     * 但改变「这个 PR 实现的是哪条需求」是需求归属问题，不是评审结论。
     *
     * <p>作者的那条路径以 409 而不是 403 结束在闸门上：角色是对的、人也是对的，
     * 挡住他的是这个 head 上已经发生的一件事实，而那件事实推一个新 commit 就能改变。
     */
    private void authorize(long projectId, ProjectMember member, PullRequest pullRequest) {
        if (member.hasRole(ProjectRole.LEADER)) {
            return;
        }
        if (!isAuthor(projectId, member, pullRequest)) {
            throw ApiException.forbidden();
        }
        if (decisions.hasFinalDecisionOnHead(projectId, pullRequest.getId(), pullRequest.getHeadSha())) {
            throw ApiException.conflict("This head already carries a final review decision; "
                    + "push a new commit, or ask the project leader to change the association.");
        }
    }

    private boolean isAuthor(long projectId, ProjectMember member, PullRequest pullRequest) {
        return member.hasRole(ProjectRole.DEVELOPER)
                && scmIdentities.isActiveAuthor(projectId, member.getUserId(),
                        pullRequest.getAuthorExternalUserId());
    }
}
