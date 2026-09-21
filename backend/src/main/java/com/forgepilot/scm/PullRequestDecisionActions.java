package com.forgepilot.scm;

/** 远端合并「人工决定所覆盖的那一个提交」。由 {@code review} 调用，实现留在 {@code scm}。 */
public interface PullRequestDecisionActions {
    void merge(long projectId, long pullRequestId, String expectedHeadSha);
}
