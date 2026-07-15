package com.example.codereview.scm;

/**
 * Provider-neutral view of a pull/merge request webhook delivery.
 *
 * <p>The required components capture everything the control plane needs to start and idempotently
 * key an Agent Run: provider, installation/project identity, repository clone URL, PR number,
 * base/head SHA, source/target branch, and the delivery id. Adapters for every provider populate
 * this same record, so downstream code never branches on the host. {@code title}, {@code author},
 * {@code repositoryFullName}, and {@code action} are best-effort context and may be absent.
 */
public record NormalizedPullRequestEvent(
        ScmProviderType provider,
        String installationRef,
        String repositoryFullName,
        String repositoryCloneUrl,
        int pullRequestNumber,
        String title,
        String author,
        String sourceBranch,
        String targetBranch,
        String baseSha,
        String headSha,
        String action,
        String deliveryId
) {
    public NormalizedPullRequestEvent {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        requireText(installationRef, "installationRef");
        requireText(repositoryCloneUrl, "repositoryCloneUrl");
        requireText(sourceBranch, "sourceBranch");
        requireText(targetBranch, "targetBranch");
        requireText(baseSha, "baseSha");
        requireText(headSha, "headSha");
        requireText(deliveryId, "deliveryId");
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
