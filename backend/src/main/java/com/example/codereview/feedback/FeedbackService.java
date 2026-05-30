package com.example.codereview.feedback;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.feedback.FeedbackDtos.FeedbackRequest;
import com.example.codereview.feedback.FeedbackDtos.FeedbackResponse;
import com.example.codereview.project.ProjectService;
import com.example.codereview.report.ReviewIssue;
import com.example.codereview.report.ReviewIssueRepository;
import com.example.codereview.report.ReviewReport;
import com.example.codereview.report.ReviewReportRepository;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackService {

    private static final Set<String> ALLOWED_TYPES = Set.of("TRUE_POSITIVE", "FALSE_POSITIVE", "NEED_DISCUSSION");

    private final FeedbackRepository feedbackRepository;
    private final ReviewIssueRepository issues;
    private final ReviewReportRepository reports;
    private final ProjectService projectService;

    public FeedbackService(FeedbackRepository feedbackRepository, ReviewIssueRepository issues,
                           ReviewReportRepository reports, ProjectService projectService) {
        this.feedbackRepository = feedbackRepository;
        this.issues = issues;
        this.reports = reports;
        this.projectService = projectService;
    }

    @Transactional
    public FeedbackResponse create(Long issueId, Long userId, FeedbackRequest request) {
        requireAccessibleIssue(issueId, userId);
        if (!ALLOWED_TYPES.contains(request.feedbackType())) {
            throw new BusinessException(400, "反馈类型不合法");
        }
        Feedback feedback = new Feedback(issueId, userId, request.feedbackType(), request.comment());
        feedbackRepository.save(feedback);
        return FeedbackResponse.from(feedback);
    }

    public List<FeedbackResponse> list(Long issueId, Long userId) {
        requireAccessibleIssue(issueId, userId);
        return feedbackRepository.findByIssueIdOrderByCreatedAtDesc(issueId)
                .stream()
                .map(FeedbackResponse::from)
                .toList();
    }

    private void requireAccessibleIssue(Long issueId, Long userId) {
        ReviewIssue issue = issues.findById(issueId)
                .orElseThrow(() -> new BusinessException(404, "审查问题不存在"));
        ReviewReport report = reports.findById(issue.getReportId())
                .orElseThrow(() -> new BusinessException(404, "审查报告不存在"));
        projectService.getRequired(report.getProjectId(), userId);
    }
}
