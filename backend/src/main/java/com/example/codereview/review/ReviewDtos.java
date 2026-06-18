package com.example.codereview.review;

import com.example.codereview.report.ReviewIssue;
import com.example.codereview.report.ReviewReport;
import java.time.Instant;
import java.util.List;

public final class ReviewDtos {

    private ReviewDtos() {
    }

    public record CreateReviewTaskRequest(
            String commitId,
            String baseCommitId,
            String branch,
            List<Long> documentIds,
            Long pullRequestId
    ) {
    }

    public record ReviewTaskResponse(
            Long taskId,
            Long pullRequestId,
            String status,
            String commitId,
            String branch,
            int retryCount,
            String errorMessage,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt
    ) {
        public static ReviewTaskResponse from(ReviewTask task) {
            return new ReviewTaskResponse(
                    task.getId(),
                    task.getPullRequestId(),
                    task.getStatus(),
                    task.getCommitId(),
                    task.getBranchName(),
                    task.getRetryCount(),
                    task.getErrorMessage(),
                    task.getCreatedAt(),
                    task.getStartedAt(),
                    task.getFinishedAt()
            );
        }
    }

    public record ReviewReportSummary(
            Long reportId,
            Long taskId,
            String commitId,
            String overallRisk,
            int issueCount,
            Instant createdAt
    ) {
        public static ReviewReportSummary from(ReviewReport report) {
            return new ReviewReportSummary(
                    report.getId(),
                    report.getTaskId(),
                    report.getCommitId(),
                    report.getOverallRisk(),
                    report.getIssueCount(),
                    report.getCreatedAt()
            );
        }
    }

    public record ReviewIssueResponse(
            Long issueId,
            String severity,
            String category,
            String filePath,
            Integer lineStart,
            Integer lineEnd,
            String title,
            String description,
            String impact,
            String evidence,
            String suggestion,
            Double confidence
    ) {
        public static ReviewIssueResponse from(ReviewIssue issue) {
            return new ReviewIssueResponse(
                    issue.getId(),
                    issue.getSeverity(),
                    issue.getCategory(),
                    issue.getFilePath(),
                    issue.getLineStart(),
                    issue.getLineEnd(),
                    issue.getTitle(),
                    issue.getDescription(),
                    issue.getImpact(),
                    issue.getEvidence(),
                    issue.getSuggestion(),
                    issue.getConfidence()
            );
        }
    }

    public record ReviewReportDetail(
            Long reportId,
            Long taskId,
            String commitId,
            String overallRisk,
            String summary,
            int issueCount,
            Instant createdAt,
            List<ReviewIssueResponse> issues
    ) {
    }
}
