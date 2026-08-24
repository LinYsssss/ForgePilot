package com.forgepilot.scm;

import org.springframework.stereotype.Service;

/** Recomputes the nullable PR-to-member projection from the live verified binding. */
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
