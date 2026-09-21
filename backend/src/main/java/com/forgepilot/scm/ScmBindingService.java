package com.forgepilot.scm;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.project.ProjectRole;
import com.forgepilot.project.ProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目成员把自己的一个 SCM 身份绑到本项目仓库（ARCHITECTURE.md 2.3「SCM 仓库稳定身份」）。
 *
 * <p>绑定是带历史的状态行，不是可覆盖的指针：每成员每项目最多一个 ACTIVE 与一个
 * PENDING_APPROVAL（V8 的两个部分唯一索引），因此新绑定前必须先把旧行 supersede/revoke，
 * 并在 {@code save} 前 {@code flush}，否则唯一索引在提交时才炸、且炸在调用栈之外。
 * 每次绑定变化都以 {@link PullRequestAuthorMapper#remapProject} 重算本项目全部 PR 的
 * {@code author_user_id}，因为那列是投影而非事实。
 *
 * <p>所有写路径先锁项目行（{@link ProjectService#lockForUpdate}），使并发的绑定/审批串行化。
 * 一次性 Token 只用于本次向 Provider 核验身份与仓库访问级别，不落库。
 */
@Service
class ScmBindingService {
    private final ProjectService projects;
    private final ProjectAccessService access;
    private final ScmRepositoryRepository repositories;
    private final ScmIdentityRepository identities;
    private final ProjectMemberScmBindingRepository bindings;
    private final ScmIdentityVerifier verifier;
    private final PullRequestAuthorMapper authors;

    ScmBindingService(ProjectService projects, ProjectAccessService access,
            ScmRepositoryRepository repositories, ScmIdentityRepository identities,
            ProjectMemberScmBindingRepository bindings, ScmIdentityVerifier verifier,
            PullRequestAuthorMapper authors) {
        this.projects = projects;
        this.access = access;
        this.repositories = repositories;
        this.identities = identities;
        this.bindings = bindings;
        this.verifier = verifier;
        this.authors = authors;
    }

    @Transactional(readOnly = true)
    List<ScmIdentityResponse> options(long projectId, long userId) {
        access.requireMember(projectId, userId);
        ScmRepository repository = repositories.findByProjectId(projectId).stream().findFirst().orElse(null);
        if (repository == null) {
            return List.of();
        }
        return identities.findByUserIdOrderByIdAsc(userId).stream()
                .filter(ScmIdentity::isVerified)
                .filter(identity -> identity.getProvider() == repository.getProvider())
                .filter(identity -> repository.getInstanceIdentity().equals(identity.getInstanceIdentity()))
                .map(ScmIdentityResponse::of).toList();
    }

    @Transactional(readOnly = true)
    List<ScmBindingResponse> list(long projectId, long actorId) {
        var member = access.requireMember(projectId, actorId);
        List<ProjectMemberScmBinding> rows = bindings.findByProjectIdOrderByIdAsc(projectId).stream()
                .filter(row -> member.hasRole(ProjectRole.LEADER) || row.getUserId() == actorId).toList();
        Map<Long, ScmIdentity> byId = identities.findAllById(
                rows.stream().map(ProjectMemberScmBinding::getScmIdentityId).toList()).stream()
                .collect(Collectors.toMap(ScmIdentity::getId, identity -> identity));
        return rows.stream().map(row -> ScmBindingResponse.of(row, byId.get(row.getScmIdentityId()))).toList();
    }

    @Transactional
    ScmBindingResponse bind(long projectId, long userId, long identityId, String token) {
        projects.lockForUpdate(projectId);
        access.requireMember(projectId, userId);
        ScmRepository repository = repository(projectId);
        ScmIdentity identity = identities.findByUserIdAndId(userId, identityId)
                .filter(ScmIdentity::isVerified).orElseThrow(ApiException::notFound);
        VerifiedScmUser current = verifier.currentUser(repository.getProvider(), repository.getApiBase(), token);
        if (identity.getProvider() != repository.getProvider()
                || !identity.getInstanceIdentity().equals(repository.getInstanceIdentity())
                || !identity.getExternalUserId().equals(current.externalUserId())) {
            throw ApiException.unprocessable("该已验证身份与本仓库不匹配。");
        }
        ProjectMemberScmBinding.AccessLevel level = verifier.repositoryAccess(repository, token);
        Instant now = Instant.now();
        bindings.findByProjectIdAndUserIdAndStatus(projectId, userId,
                ProjectMemberScmBinding.Status.PENDING_APPROVAL).ifPresent(row -> row.revoke(now));
        if (!repository.isIdentityApprovalRequired()) {
            bindings.findByProjectIdAndUserIdAndStatus(projectId, userId,
                    ProjectMemberScmBinding.Status.ACTIVE).ifPresent(row -> row.supersede(now));
        }
        bindings.flush();
        ProjectMemberScmBinding binding = bindings.save(new ProjectMemberScmBinding(projectId, userId,
                identityId, repository.getId(), level, now, repository.isIdentityApprovalRequired()));
        bindings.flush();
        authors.remapProject(projectId);
        return ScmBindingResponse.of(binding, identity);
    }

    @Transactional
    void decide(long projectId, long leaderId, long bindingId, boolean approve) {
        projects.lockForUpdate(projectId);
        access.requireRole(projectId, leaderId, ProjectRole.LEADER);
        ProjectMemberScmBinding pending = bindings.findByProjectIdAndId(projectId, bindingId)
                .filter(row -> row.getStatus() == ProjectMemberScmBinding.Status.PENDING_APPROVAL)
                .orElseThrow(ApiException::notFound);
        Instant now = Instant.now();
        if (!approve) {
            pending.reject(leaderId, now);
            return;
        }
        bindings.findByProjectIdAndUserIdAndStatus(projectId, pending.getUserId(),
                ProjectMemberScmBinding.Status.ACTIVE).ifPresent(row -> row.supersede(now));
        bindings.flush();
        pending.approve(leaderId, now);
        bindings.flush();
        authors.remapProject(projectId);
    }

    @Transactional
    void revoke(long projectId, long userId, long bindingId) {
        access.requireMember(projectId, userId);
        ProjectMemberScmBinding binding = bindings.findByProjectIdAndId(projectId, bindingId)
                .filter(row -> row.getUserId() == userId).orElseThrow(ApiException::notFound);
        if (binding.getStatus() != ProjectMemberScmBinding.Status.ACTIVE
                && binding.getStatus() != ProjectMemberScmBinding.Status.PENDING_APPROVAL) {
            throw ApiException.conflict("该 SCM 绑定已不再有效。");
        }
        binding.revoke(Instant.now());
        bindings.flush();
        authors.remapProject(projectId);
    }

    private ScmRepository repository(long projectId) {
        return repositories.findByProjectId(projectId).stream().findFirst()
                .orElseThrow(() -> ApiException.conflict("本项目尚未配置 SCM 仓库。"));
    }
}
