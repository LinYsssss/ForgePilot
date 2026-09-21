package com.forgepilot.scm;

import org.springframework.stereotype.Service;

/**
 * 重算 {@code pull_request.author_user_id} 这个可空投影：只有 Provider、实例、稳定外部用户 ID
 * 与某成员当前 ACTIVE 且已验证的绑定全部一致时才映射。绑定的任何变化（新建、审批、撤销、
 * 身份吊销）都要重跑整个项目，因为投影是从绑定推导的，不是事实源。
 */
@Service
class PullRequestAuthorMapper {
    private final ProjectMemberScmBindingRepository bindings;
    private final ScmIdentityRepository identities;
    private final ScmRepositoryRepository repositories;
    private final PullRequestRepository pullRequests;

    PullRequestAuthorMapper(ProjectMemberScmBindingRepository bindings, ScmIdentityRepository identities,
            ScmRepositoryRepository repositories, PullRequestRepository pullRequests) {
        this.bindings = bindings;
        this.identities = identities;
        this.repositories = repositories;
        this.pullRequests = pullRequests;
    }

    Long userIdFor(long projectId, String externalUserId) {
        ScmRepository repository = repositories.findByProjectId(projectId).stream().findFirst().orElse(null);
        if (repository == null) return null;
        return bindings.findByProjectIdAndStatus(projectId, ProjectMemberScmBinding.Status.ACTIVE).stream()
                .filter(binding -> identities.findByUserIdAndId(
                        binding.getUserId(), binding.getScmIdentityId()).filter(identity ->
                            identity.isVerified()
                            && identity.getProvider() == repository.getProvider()
                            && repository.getInstanceIdentity().equals(identity.getInstanceIdentity())
                            && externalUserId.equals(identity.getExternalUserId())).isPresent())
                .map(ProjectMemberScmBinding::getUserId).findFirst().orElse(null);
    }

    void remapProject(long projectId) {
        pullRequests.findByProjectIdOrderByIdAsc(projectId).forEach(pullRequest ->
                pullRequest.mapAuthor(userIdFor(projectId, pullRequest.getAuthorExternalUserId())));
    }
}
