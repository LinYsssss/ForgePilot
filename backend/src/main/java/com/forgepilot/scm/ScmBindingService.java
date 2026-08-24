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
            throw ApiException.unprocessable("That verified identity does not match this repository.");
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
            throw ApiException.conflict("That SCM binding is no longer active.");
        }
        binding.revoke(Instant.now());
        bindings.flush();
        authors.remapProject(projectId);
    }

    private ScmRepository repository(long projectId) {
        return repositories.findByProjectId(projectId).stream().findFirst()
                .orElseThrow(() -> ApiException.conflict("This project has no SCM repository."));
    }
}
