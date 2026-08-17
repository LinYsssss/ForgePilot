package com.example.codereview.review;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.ai.AiReviewResult;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.notify.Notifier;
import com.example.codereview.notify.ReviewNotification;
import com.example.codereview.project.ProjectEntity;
import com.example.codereview.project.ProjectRepository;
import com.example.codereview.report.ReviewIssue;
import com.example.codereview.report.ReviewIssueRepository;
import com.example.codereview.report.ReviewReport;
import com.example.codereview.report.ReviewReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ReviewResultWriter {

    private final ReviewTaskRepository tasks;
    private final ReviewReportRepository reports;
    private final ReviewIssueRepository issues;
    private final ProjectRepository projects;
    private final Notifier notifier;

    public ReviewResultWriter(ReviewTaskRepository tasks, ReviewReportRepository reports,
                              ReviewIssueRepository issues, ProjectRepository projects, Notifier notifier) {
        this.tasks = tasks;
        this.reports = reports;
        this.issues = issues;
        this.projects = projects;
        this.notifier = notifier;
    }

    @Transactional
    public void saveSuccess(Long taskId, AiReviewResult result) {
        saveSuccess(taskId, result, null);
    }

    @Transactional
    public void saveSuccess(Long taskId, AiReviewResult result, String coverageJson) {
        ReviewTask task = tasks.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_TASK_NOT_FOUND, "审查任务不存在"));
        if (task.isCanceled()) {
            return;
        }
        ReviewReport report = new ReviewReport(
                task.getId(),
                task.getProjectId(),
                task.getCommitId(),
                result.overallRisk(),
                result.issues().size(),
                result.summary(),
                result.rawResponse()
        );
        if (coverageJson != null) {
            report.attachCoverage(coverageJson);
        }
        reports.save(report);
        for (AiReviewResult.Issue issue : result.issues()) {
            issues.save(new ReviewIssue(
                    report.getId(),
                    issue.severity(),
                    issue.category(),
                    issue.filePath(),
                    issue.lineStart(),
                    issue.lineEnd(),
                    issue.title(),
                    issue.description(),
                    issue.impact(),
                    issue.evidence(),
                    issue.suggestion(),
                    issue.confidence()
            ));
        }
        task.markSuccess();
        scheduleNotification(task, report, result);
    }

    /**
     * 通知在事务提交后才发:审查若回滚就不该有通知,而且 HTTP 调用不应占着数据库连接。
     * 未开启通知渠道时注入的是 Noop 实现,这里不必判断开关。
     */
    private void scheduleNotification(ReviewTask task, ReviewReport report, AiReviewResult result) {
        int highCount = (int) result.issues().stream()
                .filter(issue -> "HIGH".equalsIgnoreCase(issue.severity()))
                .count();
        String projectName = projects.findById(task.getProjectId())
                .map(ProjectEntity::getName)
                .orElse(null);
        ReviewNotification notification = new ReviewNotification(
                task.getProjectId(),
                projectName,
                report.getId(),
                task.getCommitId(),
                result.overallRisk(),
                result.issues().size(),
                highCount,
                result.summary());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notifier.reviewCompleted(notification);
                }
            });
        } else {
            notifier.reviewCompleted(notification);
        }
    }
}
