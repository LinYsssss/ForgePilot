package com.forgepilot.scm;

/** Remote action triggered by a completed human review decision. */
public interface PullRequestDecisionActions {

    /** Applies the decision to the provider PR and removes its head branch. */
    void apply(long projectId, long pullRequestId, boolean approved);
}
