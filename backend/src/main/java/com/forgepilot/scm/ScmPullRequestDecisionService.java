package com.forgepilot.scm;

import com.forgepilot.common.ApiException;
import com.forgepilot.scm.github.GitHubClient;
import com.forgepilot.scm.gitlab.GitLabClient;
import org.springframework.stereotype.Service;

/**
 * 人工决定到 SCM Provider 的最小桥接：按项目仓库的 Provider 把「合并被审查的 SHA」
 * 交给 GitHub 或 GitLab 客户端。它是 {@code review} 唯一允许触达 {@code scm} 写操作的门面，
 * {@code scm} 自身仍不依赖 {@code review}（ARCHITECTURE.md 1.3）。
 */
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
                .orElseThrow(ApiException::notFound);
        ScmRepository repository = repositories.findByProjectIdAndId(projectId, pullRequest.getRepositoryId())
                .orElseThrow(ApiException::notFound);
        if (repository.getProvider() == ScmProvider.GITHUB) {
            github.merge(repository, pullRequest.getExternalNumber(), expectedHeadSha);
        } else {
            gitlab.merge(repository, pullRequest.getExternalNumber(), expectedHeadSha);
        }
    }
}
