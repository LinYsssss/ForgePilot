package com.forgepilot.scm;

/** Remote merge of the exact commit covered by the human decision. */
public interface PullRequestDecisionActions {
    void merge(long projectId, long pullRequestId, String expectedHeadSha);
}
