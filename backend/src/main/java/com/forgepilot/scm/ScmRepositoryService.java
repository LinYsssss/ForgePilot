package com.forgepilot.scm;

import java.net.URI;
import java.util.List;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.project.ProjectRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目活动仓库的注册与重配置。只有 LEADER 能触达其中任何一项，
 * 而非成员得到的答案与「项目不存在」完全相同。
 *
 * <p>凡是数据库已经会拒绝的事情，本服务一律不做预检查：「一个项目一个仓库」
 * 和「一个全局唯一的稳定身份」都是唯一约束，它们的冲突会自行以 409 返回。
 */
@Service
class ScmRepositoryService {

    private final ScmRepositoryRepository repositories;
    private final PullRequestRepository pullRequests;
    private final ProjectAccessService access;
    private final PullRequestAssociationService associations;
    private final OutboundUrlPolicy outbound;
    private final ScmSecretCipher cipher;

    ScmRepositoryService(ScmRepositoryRepository repositories, PullRequestRepository pullRequests,
            ProjectAccessService access, PullRequestAssociationService associations,
            OutboundUrlPolicy outbound, ScmSecretCipher cipher) {
        this.repositories = repositories;
        this.pullRequests = pullRequests;
        this.access = access;
        this.associations = associations;
        this.outbound = outbound;
        this.cipher = cipher;
    }

    /**
     * 成员可查看当前项目已经接入的仓库，但响应只投影可安全展示的连接元数据。
     * 数据库的 project_id 唯一约束使返回最多一项；保留数组形状让前端自然处理
     * 尚未接入的项目，也不需要暴露一条可空的“当前仓库”状态。
     */
    @Transactional(readOnly = true)
    List<ScmRepositoryResponse> list(long projectId, long actorId) {
        access.requireMember(projectId, actorId);
        return repositories.findByProjectId(projectId).stream().map(ScmRepositoryResponse::of).toList();
    }

    @Transactional
    ScmRepositoryResponse register(long projectId, long actorId, ScmProvider provider, String externalId,
            String apiBase, String token, String webhookSecret) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        URI base = outbound.requireAllowed(apiBase);
        ScmRepository repository = new ScmRepository(projectId, provider, InstanceIdentity.of(base),
                externalId, base.toString(), cipher.encrypt(token), cipher.encrypt(webhookSecret));
        return ScmRepositoryResponse.of(repositories.save(repository));
    }

    /**
     * 凭据永远可以替换。但稳定身份一旦该仓库有了 PR 就不能再改：
     * 所有已经记录下来的东西——指纹、关联、审计行——记录的都是关于
     * <em>那一个</em>仓库的事实，把这一行重新指向别处会悄悄地把它们全部改写归属。
     * 真要更换仓库或实例，意味着开一个新项目（PRD 8）。
     *
     * <p>这是一条跨行规则——本行的列能否改动，取决于另一张表里有没有行——
     * 因此任何即时约束都无法表达它，而 ARCHITECTURE.md 2.1 只为 {@code finding}
     * 授权了约束触发器。于是它成为一条与「至少有一个 LEADER」同类的
     * 逐次提交服务层不变式（D013.9），在先取得的行锁下于此处强制执行，
     * 并且<strong>不由数据库强制</strong>。
     */
    @Transactional
    ScmRepositoryResponse update(long projectId, long actorId, long repositoryId, ScmProvider provider,
            String externalId, String apiBase, String token, String webhookSecret,
            Boolean identityApprovalRequired) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        ScmRepository repository = repositories.findWithLockByProjectIdAndId(projectId, repositoryId)
                .orElseThrow(ApiException::notFound);

        ScmProvider targetProvider = provider == null ? repository.getProvider() : provider;
        String targetExternalId = externalId == null ? repository.getExternalId() : externalId;
        URI base = apiBase == null ? null : outbound.requireAllowed(apiBase);
        // 只要归一化之后仍落到同一个实例上，api_base 就可以自由变动：
        // API host 可能随 provider 版本变化，但实例本身不能在一个仓库脚下换掉。
        String targetInstance = base == null ? repository.getInstanceIdentity() : InstanceIdentity.of(base);

        boolean identityMoves = targetProvider != repository.getProvider()
                || !targetExternalId.equals(repository.getExternalId())
                || !targetInstance.equals(repository.getInstanceIdentity());
        if (identityMoves && pullRequests.existsByProjectIdAndRepositoryId(projectId, repositoryId)) {
            throw ApiException.conflict(
                    "This repository already has pull requests, so its identity can no longer change.");
        }

        if (identityMoves) {
            repository.reidentify(targetProvider, targetInstance, targetExternalId);
        }
        if (base != null) {
            repository.changeApiBase(base.toString());
        }
        if (token != null || webhookSecret != null) {
            repository.rotateCredentials(
                    token == null ? repository.getEncryptedToken() : cipher.encrypt(token),
                    webhookSecret == null ? repository.getEncryptedSecret() : cipher.encrypt(webhookSecret));
        }
        if (identityApprovalRequired != null) {
            repository.changeIdentityApprovalRequired(identityApprovalRequired);
        }
        return ScmRepositoryResponse.of(repository);
    }

    @Transactional(readOnly = true)
    PullRequestResponse pullRequest(long projectId, long actorId, long pullRequestId) {
        var member = access.requireMember(projectId, actorId);
        return pullRequests.findByProjectIdAndId(projectId, pullRequestId)
                .map(pullRequest -> PullRequestResponse.of(pullRequest,
                        associations.canCorrect(projectId, member, pullRequest)))
                .orElseThrow(ApiException::notFound);
    }
}
