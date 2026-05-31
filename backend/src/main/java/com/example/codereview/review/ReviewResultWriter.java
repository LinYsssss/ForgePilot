package com.example.codereview.review;

import com.example.codereview.ai.AiReviewResult;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.report.ReviewIssue;
import com.example.codereview.report.ReviewIssueRepository;
import com.example.codereview.report.ReviewReport;
import com.example.codereview.report.ReviewReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewResultWriter {

    private final ReviewTaskRepository tasks;
    private final ReviewReportRepository reports;
    private final ReviewIssueRepository issues;

    public ReviewResultWriter(ReviewTaskRepository tasks, ReviewReportRepository reports, ReviewIssueRepository issues) {
        this.tasks = tasks;
        this.reports = reports;
        this.issues = issues;
    }

    @Transactional
    public void saveSuccess(Long taskId, AiReviewResult result) {
        ReviewTask task = tasks.findById(taskId)
                .orElseThrow(() -> new BusinessException(6002, "审查任务不存在"));
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
    }
}
