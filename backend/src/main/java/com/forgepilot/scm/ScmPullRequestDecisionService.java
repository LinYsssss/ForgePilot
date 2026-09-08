package com.forgepilot.scm;

import com.forgepilot.scm.github.GitHubClient;
import com.forgepilot.scm.gitlab.GitLabClient;
import org.springframework.stereotype.Service;

/** Minimal bridge from the review decision to the configured SCM provider. */
@Service
class ScmPullRequestDecisionService implements PullRequestDecisionActions {

    private final ScmRepositoryRepository repositories;
    private final PullRequestRepository pullRequests;
    private final GitHubClient github;
    private final GitLabClient gitlab;

    ScmPullRequestDecisionService(ScmRepositoryRepository repositories, PullRequestRepository pullRequests,
            GitHubClient github, GitLabClient gitlab) {
        this.repositories = repositories;
        this.pullRequests = pullRequests;
        this.github = github;
        this.gitlab = gitlab;
    }

    @Override
    public void merge(long projectId, long pullRequestId, String expectedHeadSha) {
        PullRequest pullRequest = pullRequests.findByProjectIdAndId(projectId, pullRequestId)
                .orElseThrow();
        ScmRepository repository = repositories.findByProjectIdAndId(projectId, pullRequest.getRepositoryId())
                .orElseThrow();
        if (repository.getProvider() == ScmProvider.GITHUB) {
            github.merge(repository, pullRequest.getExternalNumber(), expectedHeadSha);
        } else {
            gitlab.merge(repository, pullRequest.getExternalNumber(), expectedHeadSha);
        }
    }
}
