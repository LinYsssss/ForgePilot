package com.forgepilot.scm;

import java.time.Instant;

/**
 * PR 存下来的快照。变更文件清单刻意缺席：批次 2 里没有任何东西消费它，
 * 而它是一份 review 量级的载荷。
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
        boolean canEditRequirementAssociation,
        Instant sourceUpdatedAt,
        Instant updatedAt) {

    static PullRequestResponse of(PullRequest pullRequest, boolean canEditRequirementAssociation) {
        return new PullRequestResponse(pullRequest.getId(), pullRequest.getProjectId(),
                pullRequest.getRepositoryId(), pullRequest.getExternalNumber(), pullRequest.getBaseSha(),
                pullRequest.getHeadSha(), pullRequest.getReviewInputFingerprint(),
                pullRequest.getRequirementId(), pullRequest.getAuthorExternalUserId(),
                pullRequest.getAuthorUsername(), pullRequest.getAuthorUserId(), canEditRequirementAssociation,
                pullRequest.getSourceUpdatedAt(), pullRequest.getUpdatedAt());
    }
}
