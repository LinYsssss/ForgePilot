package com.example.codereview.feedback;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.auth.UserAccount;
import com.example.codereview.auth.UserAccountRepository;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.feedback.FeedbackDtos.FeedbackRequest;
import com.example.codereview.feedback.FeedbackDtos.FeedbackResponse;
import com.example.codereview.project.ProjectService;
import com.example.codereview.report.ReviewIssue;
import com.example.codereview.report.ReviewIssueRepository;
import com.example.codereview.report.ReviewReport;
import com.example.codereview.report.ReviewReportRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final UserAccountRepository users;

    public FeedbackService(FeedbackRepository feedbackRepository, ReviewIssueRepository issues,
                           ReviewReportRepository reports, ProjectService projectService,
                           UserAccountRepository users) {
        this.feedbackRepository = feedbackRepository;
        this.issues = issues;
        this.reports = reports;
        this.projectService = projectService;
        this.users = users;
    }

    @Transactional
    public FeedbackResponse create(Long issueId, Long userId, FeedbackRequest request) {
        requireAccessibleIssue(issueId, userId);
        if (!ALLOWED_TYPES.contains(request.feedbackType())) {
            throw new BusinessException(400, "反馈类型不合法");
        }
        Feedback feedback = feedbackRepository.findByIssueIdAndUserId(issueId, userId)
                .map(existing -> {
                    existing.update(request.feedbackType(), request.comment());
                    return existing;
                })
                .orElseGet(() -> new Feedback(issueId, userId, request.feedbackType(), request.comment()));
        feedbackRepository.save(feedback);
        return FeedbackResponse.from(feedback, displayName(userId), userId);
    }

    @Transactional
    public void delete(Long issueId, Long userId) {
        requireAccessibleIssue(issueId, userId);
        Feedback feedback = feedbackRepository.findByIssueIdAndUserId(issueId, userId)
                .orElseThrow(() -> new BusinessException(404, "你还没有提交过反馈"));
        feedbackRepository.delete(feedback);
    }

    @Transactional(readOnly = true)
    public List<FeedbackResponse> list(Long issueId, Long userId) {
        requireAccessibleIssue(issueId, userId);
        List<Feedback> all = feedbackRepository.findByIssueIdOrderByCreatedAtDesc(issueId);
        Map<Long, String> nameCache = new HashMap<>();
        return all.stream()
                .map(fb -> FeedbackResponse.from(
                        fb,
                        nameCache.computeIfAbsent(fb.getUserId(), this::displayName),
                        userId))
                .toList();
    }

    private String displayName(Long userId) {
        return users.findById(userId)
                .map(user -> {
                    String nickname = user.getNickname();
                    return nickname == null || nickname.isBlank() ? user.getUsername() : nickname;
                })
                .orElse("用户#" + userId);
    }

    private void requireAccessibleIssue(Long issueId, Long userId) {
        ReviewIssue issue = issues.findById(issueId)
                .orElseThrow(() -> new BusinessException(404, "审查问题不存在"));
        ReviewReport report = reports.findById(issue.getReportId())
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_REPORT_NOT_FOUND, "审查报告不存在"));
        projectService.getRequired(report.getProjectId(), userId);
    }
}
