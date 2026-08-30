package com.forgepilot.scm;

import com.forgepilot.scm.github.GitHubClient;
import org.springframework.stereotype.Service;

/** Minimal bridge from the review decision to the configured SCM provider. */
@Service
class ScmPullRequestDecisionService implements PullRequestDecisionActions {

    private final ScmRepositoryRepository repositories;
    private final PullRequestRepository pullRequests;
    private final GitHubClient github;

    ScmPullRequestDecisionService(ScmRepositoryRepository repositories, PullRequestRepository pullRequests,
            GitHubClient github) {
        this.repositories = repositories;
        this.pullRequests = pullRequests;
        this.github = github;
    }

    @Override
    public void apply(long projectId, long pullRequestId, boolean approved) {
        PullRequest pullRequest = pullRequests.findByProjectIdAndId(projectId, pullRequestId)
                .orElseThrow();
        ScmRepository repository = repositories.findByProjectIdAndId(projectId, pullRequest.getRepositoryId())
                .orElseThrow();
        if (repository.getProvider() == ScmProvider.GITHUB) {
            github.applyDecision(repository, pullRequest.getExternalNumber(), approved);
        }
    }
}
