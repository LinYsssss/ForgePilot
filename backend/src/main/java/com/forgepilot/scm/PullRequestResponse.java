package com.forgepilot.scm;

import java.time.Instant;

/**
 * The stored snapshot of a pull request. The changed-file manifest is deliberately
 * absent: nothing in batch 2 consumes it, and it is a review-sized payload.
 */
record PullRequestResponse(
        Long id,
        Long projectId,
        Long repositoryId,
        Integer externalNumber,
        String baseSha,
        String headSha,
        String reviewInputFingerprint,
        Long requirementId,
        String authorExternalUserId,
        String authorUsername,
        Long authorUserId,
        Instant sourceUpdatedAt,
        Instant updatedAt) {

    static PullRequestResponse of(PullRequest pullRequest) {
        return new PullRequestResponse(pullRequest.getId(), pullRequest.getProjectId(),
                pullRequest.getRepositoryId(), pullRequest.getExternalNumber(), pullRequest.getBaseSha(),
                pullRequest.getHeadSha(), pullRequest.getReviewInputFingerprint(),
                pullRequest.getRequirementId(), pullRequest.getAuthorExternalUserId(),
                pullRequest.getAuthorUsername(), pullRequest.getAuthorUserId(),
                pullRequest.getSourceUpdatedAt(), pullRequest.getUpdatedAt());
    }
}
