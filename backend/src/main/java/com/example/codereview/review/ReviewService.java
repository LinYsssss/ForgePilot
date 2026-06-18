package com.example.codereview.review;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.ai.AiCallLogRepository;
import com.example.codereview.feedback.FeedbackRepository;
import com.example.codereview.mq.MqTaskLogRepository;
import com.example.codereview.mq.ReviewTaskMessage;
import com.example.codereview.mq.ReviewTaskPublisher;
import com.example.codereview.pullrequest.PullRequestEntity;
import com.example.codereview.pullrequest.PullRequestRepository;
import com.example.codereview.repo.CodeRepositoryEntity;
import com.example.codereview.repo.RepositoryDtos.CommitDiffResponse;
import com.example.codereview.repo.RepositoryDtos.CommitResponse;
import com.example.codereview.repo.RepositoryService;
import com.example.codereview.report.ReviewIssue;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

@Service
public class ReviewService {

    private final RepositoryService repositoryService;
    private final ReviewTaskRepository tasks;
    private final ReviewReportRepository reports;
    private final ReviewIssueRepository issues;
    private final ReviewProcessor processor;
    private final ReviewTaskPublisher publisher;
    private final PullRequestRepository pullRequests;
    private final FeedbackRepository feedback;
    private final MqTaskLogRepository mqLogs;
    private final AiCallLogRepository aiCallLogs;
    private final boolean inline;
    private final int maxTotalDiffChars;

    public ReviewService(RepositoryService repositoryService, ReviewTaskRepository tasks, ReviewReportRepository reports,
                         ReviewIssueRepository issues, ReviewProcessor processor, ReviewTaskPublisher publisher,
                         PullRequestRepository pullRequests, FeedbackRepository feedback,
                         MqTaskLogRepository mqLogs, AiCallLogRepository aiCallLogs,
                         @Value("${app.review.inline}") boolean inline,
                         @Value("${app.review.max-total-diff-chars:200000}") int maxTotalDiffChars) {
        this.repositoryService = repositoryService;
        this.tasks = tasks;
        this.reports = reports;
        this.issues = issues;
        this.processor = processor;
        this.publisher = publisher;
        this.pullRequests = pullRequests;
        this.feedback = feedback;
        this.mqLogs = mqLogs;
        this.aiCallLogs = aiCallLogs;
        this.inline = inline;
        this.maxTotalDiffChars = maxTotalDiffChars;
    }

    public ReviewTaskResponse create(Long projectId, Long userId, CreateReviewTaskRequest request) {
        CodeRepositoryEntity repository = repositoryService.getRequired(projectId, userId);
        PullRequestEntity pullRequest = resolvePullRequest(projectId, request.pullRequestId());
        if (pullRequest != null && !pullRequest.getRepositoryId().equals(repository.getId())) {
            throw new BusinessException(400, "PR 不属于当前仓库");
        }
        String commitId = pullRequest == null ? resolveCommitId(projectId, userId, request.commitId()) : pullRequest.getHeadSha();
        String baseCommitId = pullRequest == null ? request.baseCommitId() : pullRequest.getBaseSha();
        String branchName = resolveBranchName(repository, request.branch(), pullRequest);
        CommitDiffResponse diff = repositoryService.diff(projectId, userId, commitId, baseCommitId);
        ReviewTask existing = tasks
                .findIdempotentTask(
                        projectId,
                        repository.getId(),
                        commitId,
                        normalizeBaseCommitId(diff.baseCommitId()),
                        branchName
                )
                .orElse(null);
        if (existing != null) {
            return ReviewTaskResponse.from(existing);
        }
        ReviewTask task = saveTaskWithRetry(
                projectId,
                repository.getId(),
                commitId,
                diff.baseCommitId(),
                branchName,
                userId,
                truncate(diff.rawDiff()),
                request.documentIds(),
                pullRequest
        );
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

    private ReviewTask saveTaskWithRetry(Long projectId, Long repositoryId, String commitId, String baseCommitId,
                                         String branchName, Long userId, String rawDiff, List<Long> documentIds,
                                         PullRequestEntity pullRequest) {
        ReviewTask task = new ReviewTask(
                projectId,
                repositoryId,
                commitId,
                baseCommitId,
                branchName,
                userId,
                rawDiff,
                documentIds,
                pullRequest == null ? null : pullRequest.getId()
        );
        try {
            return tasks.saveAndFlush(task);
        } catch (DataIntegrityViolationException ex) {
            return tasks.findIdempotentTask(projectId, repositoryId, commitId, normalizeBaseCommitId(baseCommitId), branchName)
                    .orElseThrow(() -> ex);
        }
    }

    private String normalizeBaseCommitId(String baseCommitId) {
        return baseCommitId == null ? "" : baseCommitId;
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

    @Transactional
    public ReviewTaskResponse cancelTask(Long projectId, Long userId, Long taskId) {
        repositoryService.getRequired(projectId, userId);
        ReviewTask task = requireTask(projectId, taskId);
        if (task.isTerminal()) {
            throw new BusinessException(6003, "任务已结束，无法停止");
        }
        task.markCanceled();
        tasks.save(task);
        return ReviewTaskResponse.from(task);
    }

    @Transactional
    public void deleteTask(Long projectId, Long userId, Long taskId) {
        repositoryService.getRequired(projectId, userId);
        ReviewTask task = requireTask(projectId, taskId);
        reports.findByTaskId(taskId).ifPresent(report -> purgeReport(report));
        aiCallLogs.deleteByTaskId(taskId);
        mqLogs.deleteByTaskId(taskId);
        tasks.delete(task);
    }

    @Transactional
    public void deleteReport(Long projectId, Long userId, Long reportId) {
        repositoryService.getRequired(projectId, userId);
        ReviewReport report = reports.findById(reportId)
                .orElseThrow(() -> new BusinessException(404, "审查报告不存在"));
        if (!report.getProjectId().equals(projectId)) {
            throw new BusinessException(403, "无权访问该报告");
        }
        purgeReport(report);
    }

    private void purgeReport(ReviewReport report) {
        List<Long> issueIds = issues.findByReportId(report.getId())
                .stream().map(ReviewIssue::getId).toList();
        if (!issueIds.isEmpty()) {
            feedback.deleteByIssueIdIn(issueIds);
        }
        issues.deleteByReportIdIn(List.of(report.getId()));
        reports.delete(report);
    }

    private ReviewTask requireTask(Long projectId, Long taskId) {
        ReviewTask task = tasks.findById(taskId)
                .orElseThrow(() -> new BusinessException(6002, "审查任务不存在"));
        if (!task.getProjectId().equals(projectId)) {
            throw new BusinessException(403, "无权访问该任务");
        }
        return task;
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

    private PullRequestEntity resolvePullRequest(Long projectId, Long pullRequestId) {
        if (pullRequestId == null) {
            return null;
        }
        PullRequestEntity pullRequest = pullRequests.findById(pullRequestId)
                .orElseThrow(() -> new BusinessException(404, "PR 不存在"));
        if (!pullRequest.getProjectId().equals(projectId)) {
            throw new BusinessException(403, "PR 不属于当前项目");
        }
        return pullRequest;
    }

    private String resolveBranchName(CodeRepositoryEntity repository, String requestedBranch, PullRequestEntity pullRequest) {
        if (pullRequest != null) {
            return pullRequest.getSourceBranch();
        }
        return requestedBranch == null || requestedBranch.isBlank() ? repository.getDefaultBranch() : requestedBranch;
    }

    private String truncate(String diff) {
        if (diff == null) {
            return "";
        }
        if (diff.length() <= maxTotalDiffChars) {
            return diff;
        }
        // Overall safety cap only; per-file chunking (ReviewProcessor) handles fitting the model
        // context, so this bound is large and rarely hit.
        return diff.substring(0, maxTotalDiffChars) + "\n\n[Diff 已截断，超过单任务最大长度 " + maxTotalDiffChars + "]";
    }
}
