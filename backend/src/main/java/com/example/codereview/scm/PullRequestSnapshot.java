package com.example.codereview.scm;

/**
 * Point-in-time metadata for a pull/merge request, fetched from the provider API after a delivery
 * is verified. Distinct from {@link NormalizedPullRequestEvent}: the event is what the webhook told
 * us, the snapshot is what the provider confirms now (and may include the authoritative head/base
 * SHA used to drive the review). Provider-neutral so the agent pipeline never branches on host.
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
