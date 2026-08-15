package com.example.codereview.scm;

/**
 * pull/merge request 的时点元数据,在投递验签通过后从 provider API 取回。
 * 它与 {@link NormalizedPullRequestEvent} 是两回事:事件是 webhook **当时告诉我们的**,
 * 快照是 provider **此刻确认的**(并且可能带上驱动本次审查所用的权威 head/base SHA)。
 * 同样与 provider 无关,因此 agent 流水线永远不必按托管方分支。
 */
public record PullRequestSnapshot(
        ScmProviderType provider,
        String repositoryFullName,
        String repositoryCloneUrl,
        int pullRequestNumber,
        String title,
        String author,
        String sourceBranch,
        String targetBranch,
        String baseSha,
        String headSha,
        String state
) {
    public PullRequestSnapshot {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        requireText(repositoryCloneUrl, "repositoryCloneUrl");
        requireText(sourceBranch, "sourceBranch");
        requireText(targetBranch, "targetBranch");
        requireText(baseSha, "baseSha");
        requireText(headSha, "headSha");
        requireText(state, "state");
        if (pullRequestNumber <= 0) {
            throw new IllegalArgumentException("pullRequestNumber must be positive");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
