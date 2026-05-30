package com.example.codereview.review;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.mq.ReviewTaskMessage;
import com.example.codereview.mq.ReviewTaskPublisher;
import com.example.codereview.repo.CodeRepositoryEntity;
import com.example.codereview.repo.RepositoryDtos.CommitDiffResponse;
import com.example.codereview.repo.RepositoryDtos.CommitResponse;
import com.example.codereview.repo.RepositoryService;
import com.example.codereview.report.ReviewIssueRepository;
import com.example.codereview.report.ReviewReport;
import com.example.codereview.report.ReviewReportRepository;
import com.example.codereview.review.ReviewDtos.CreateReviewTaskRequest;
import com.example.codereview.review.ReviewDtos.ReviewIssueResponse;
import com.example.codereview.review.ReviewDtos.ReviewReportDetail;
import com.example.codereview.review.ReviewDtos.ReviewReportSummary;
import com.example.codereview.review.ReviewDtos.ReviewTaskResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ReviewService {

    private final RepositoryService repositoryService;
    private final ReviewTaskRepository tasks;
    private final ReviewReportRepository reports;
    private final ReviewIssueRepository issues;
    private final ReviewProcessor processor;
    private final ReviewTaskPublisher publisher;
    private final boolean inline;
    private final int maxDiffChars;

    public ReviewService(RepositoryService repositoryService, ReviewTaskRepository tasks, ReviewReportRepository reports,
                         ReviewIssueRepository issues, ReviewProcessor processor, ReviewTaskPublisher publisher,
                         @Value("${app.review.inline}") boolean inline,
                         @Value("${app.review.max-diff-chars}") int maxDiffChars) {
        this.repositoryService = repositoryService;
        this.tasks = tasks;
        this.reports = reports;
        this.issues = issues;
        this.processor = processor;
        this.publisher = publisher;
        this.inline = inline;
        this.maxDiffChars = maxDiffChars;
    }

    public ReviewTaskResponse create(Long projectId, Long userId, CreateReviewTaskRequest request) {
        CodeRepositoryEntity repository = repositoryService.getRequired(projectId, userId);
        String commitId = resolveCommitId(projectId, userId, request.commitId());
        String branchName = request.branch() == null || request.branch().isBlank() ? repository.getDefaultBranch() : request.branch();
        CommitDiffResponse diff = repositoryService.diff(projectId, userId, commitId, request.baseCommitId());
        ReviewTask existing = tasks
                .findFirstByProjectIdAndRepositoryIdAndCommitIdAndBaseCommitIdAndBranchNameOrderByCreatedAtDesc(
                        projectId,
                        repository.getId(),
                        commitId,
                        diff.baseCommitId(),
                        branchName
                )
                .orElse(null);
        if (existing != null) {
            return ReviewTaskResponse.from(existing);
        }
        String rawDiff = truncate(diff.rawDiff());
        ReviewTask task = new ReviewTask(
                projectId,
                repository.getId(),
                commitId,
                diff.baseCommitId(),
                branchName,
                userId,
                rawDiff
        );
        tasks.save(task);
        if (inline) {
            processor.process(task.getId());
            return tasks.findById(task.getId())
                    .map(ReviewTaskResponse::from)
                    .orElseGet(() -> ReviewTaskResponse.from(task));
        } else {
            publisher.publish(ReviewTaskMessage.of(task.getId(), projectId, commitId));
        }
        return ReviewTaskResponse.from(task);
    }

    public List<ReviewTaskResponse> listTasks(Long projectId, Long userId) {
        repositoryService.getRequired(projectId, userId);
        return tasks.findByProjectIdOrderByCreatedAtDesc(projectId)
                .stream()
                .map(ReviewTaskResponse::from)
                .toList();
    }

    public ReviewTaskResponse taskDetail(Long projectId, Long userId, Long taskId) {
        repositoryService.getRequired(projectId, userId);
        ReviewTask task = tasks.findById(taskId)
                .orElseThrow(() -> new BusinessException(6002, "审查任务不存在"));
        if (!task.getProjectId().equals(projectId)) {
            throw new BusinessException(403, "无权访问该任务");
        }
        return ReviewTaskResponse.from(task);
    }

    public List<ReviewReportSummary> reports(Long projectId, Long userId) {
        repositoryService.getRequired(projectId, userId);
        return reports.findByProjectIdOrderByCreatedAtDesc(projectId)
                .stream()
                .map(ReviewReportSummary::from)
                .toList();
    }

    public ReviewReportDetail reportDetail(Long projectId, Long userId, Long reportId) {
        repositoryService.getRequired(projectId, userId);
        ReviewReport report = reports.findById(reportId)
                .orElseThrow(() -> new BusinessException(404, "审查报告不存在"));
        if (!report.getProjectId().equals(projectId)) {
            throw new BusinessException(403, "无权访问该报告");
        }
        List<ReviewIssueResponse> issueResponses = issues.findByReportId(report.getId())
                .stream()
                .map(ReviewIssueResponse::from)
                .toList();
        return new ReviewReportDetail(
                report.getId(),
                report.getTaskId(),
                report.getCommitId(),
                report.getOverallRisk(),
                report.getSummary(),
                report.getIssueCount(),
                report.getCreatedAt(),
                issueResponses
        );
    }

    private String resolveCommitId(Long projectId, Long userId, String requestedCommitId) {
        if (requestedCommitId != null && !requestedCommitId.isBlank()) {
            return requestedCommitId;
        }
        List<CommitResponse> commits = repositoryService.commits(projectId, userId, 1);
        if (commits.isEmpty()) {
            throw new BusinessException(6001, "仓库没有可审查的 Commit");
        }
        return commits.get(0).commitId();
    }

    private String truncate(String diff) {
        if (diff == null) {
            return "";
        }
        if (diff.length() <= maxDiffChars) {
            return diff;
        }
        return diff.substring(0, maxDiffChars) + "\n\n[Diff 已截断，超过最大长度 " + maxDiffChars + "]";
    }
}
