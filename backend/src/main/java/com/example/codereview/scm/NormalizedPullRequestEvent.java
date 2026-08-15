package com.example.codereview.scm;

/**
 * pull/merge request webhook 投递的 provider 无关视图。
 *
 * <p>必填分量涵盖了控制面启动一次 Agent Run、并为其生成幂等键所需的全部信息:provider、
 * installation/project 身份、仓库 clone URL、PR 号、base/head SHA、源/目标分支,以及 delivery id。
 * 所有 provider 的适配器都填同一个 record,因此下游代码永远不必按托管方分支。
 * {@code title}、{@code author}、{@code repositoryFullName}、{@code action} 属于尽力而为的上下文,
 * 可能缺失。
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
