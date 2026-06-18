package com.example.codereview.pullrequest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class PullRequestDtos {

    private PullRequestDtos() {
    }

    public record CreatePullRequestRequest(
            Integer prNumber,
            @NotBlank @Size(max = 255) String title,
            @Size(max = 128) String authorName,
            @NotBlank @Size(max = 128) String sourceBranch,
            @NotBlank @Size(max = 128) String targetBranch,
            @NotBlank @Size(max = 80) String baseSha,
            @NotBlank @Size(max = 80) String headSha,
            @Size(max = 32) String provider,
            @Size(max = 128) String externalPrId
    ) {
    }

    public record UpdatePullRequestRequest(
            Integer prNumber,
            @NotBlank @Size(max = 255) String title,
            @Size(max = 128) String authorName,
            @NotBlank @Size(max = 128) String sourceBranch,
            @NotBlank @Size(max = 128) String targetBranch,
            @NotBlank @Size(max = 80) String baseSha,
            @NotBlank @Size(max = 80) String headSha,
            @Size(max = 32) String provider,
            @Size(max = 128) String externalPrId,
            @Size(max = 32) String status
    ) {
    }

    public record CreatePullRequestReviewTaskRequest(
            List<Long> documentIds
    ) {
    }

    public record ReviewActionRequest(
            @NotBlank @Size(max = 32) String actionType,
            Long reportId,
            @Size(max = 2000) String reason,
            @Size(max = 4000) String requirement,
            List<Long> selectedIssueIds
    ) {
    }

    public record PullRequestResponse(
            Long pullRequestId,
            Long projectId,
            Long repositoryId,
            String provider,
            String externalPrId,
            Integer prNumber,
            String title,
            String authorName,
            String sourceBranch,
            String targetBranch,
            String baseSha,
            String headSha,
            String status,
            String reviewState,
            Instant lastSyncedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static PullRequestResponse from(PullRequestEntity entity) {
            return new PullRequestResponse(
                    entity.getId(),
                    entity.getProjectId(),
                    entity.getRepositoryId(),
                    entity.getProvider(),
                    entity.getExternalPrId(),
                    entity.getPrNumber(),
                    entity.getTitle(),
                    entity.getAuthorName(),
                    entity.getSourceBranch(),
                    entity.getTargetBranch(),
                    entity.getBaseSha(),
                    entity.getHeadSha(),
                    entity.getStatus(),
                    entity.getReviewState(),
                    entity.getLastSyncedAt(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt()
            );
        }
    }

    public record ReviewActionResponse(
            Long actionId,
            Long pullRequestId,
            Long reportId,
            Long actorId,
            String actionType,
            String reason,
            String requirement,
            List<Long> selectedIssueIds,
            Instant createdAt
    ) {
        public static ReviewActionResponse from(ReviewAction action) {
            return new ReviewActionResponse(
                    action.getId(),
                    action.getPullRequestId(),
                    action.getReportId(),
                    action.getActorId(),
                    action.getActionType(),
                    action.getReason(),
                    action.getRequirementText(),
                    action.getSelectedIssueIds(),
                    action.getCreatedAt()
            );
        }
    }
}
