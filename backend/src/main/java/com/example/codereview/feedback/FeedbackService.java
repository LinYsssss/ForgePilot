package com.example.codereview.feedback;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.feedback.FeedbackDtos.FeedbackRequest;
import com.example.codereview.feedback.FeedbackDtos.FeedbackResponse;
import com.example.codereview.report.ReviewIssueRepository;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackService {

    private static final Set<String> ALLOWED_TYPES = Set.of("TRUE_POSITIVE", "FALSE_POSITIVE", "NEED_DISCUSSION");

    private final FeedbackRepository feedbackRepository;
    private final ReviewIssueRepository issues;

    public FeedbackService(FeedbackRepository feedbackRepository, ReviewIssueRepository issues) {
        this.feedbackRepository = feedbackRepository;
        this.issues = issues;
    }

    @Transactional
    public FeedbackResponse create(Long issueId, Long userId, FeedbackRequest request) {
        if (!issues.existsById(issueId)) {
            throw new BusinessException(404, "审查问题不存在");
        }
        if (!ALLOWED_TYPES.contains(request.feedbackType())) {
            throw new BusinessException(400, "反馈类型不合法");
        }
        Feedback feedback = new Feedback(issueId, userId, request.feedbackType(), request.comment());
        feedbackRepository.save(feedback);
        return FeedbackResponse.from(feedback);
    }

    public List<FeedbackResponse> list(Long issueId) {
        if (!issues.existsById(issueId)) {
            throw new BusinessException(404, "审查问题不存在");
        }
        return feedbackRepository.findByIssueIdOrderByCreatedAtDesc(issueId)
                .stream()
                .map(FeedbackResponse::from)
                .toList();
    }
}
