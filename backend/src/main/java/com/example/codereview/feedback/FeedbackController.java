package com.example.codereview.feedback;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import com.example.codereview.feedback.FeedbackDtos.FeedbackRequest;
import com.example.codereview.feedback.FeedbackDtos.FeedbackResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/review-issues/{issueId}/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final CurrentUserProvider currentUserProvider;

    public FeedbackController(FeedbackService feedbackService, CurrentUserProvider currentUserProvider) {
        this.feedbackService = feedbackService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public ApiResponse<FeedbackResponse> create(@PathVariable Long issueId, @Valid @RequestBody FeedbackRequest request) {
        return ApiResponse.ok(feedbackService.create(issueId, currentUserProvider.getRequired().userId(), request));
    }

    @GetMapping
    public ApiResponse<List<FeedbackResponse>> list(@PathVariable Long issueId) {
        return ApiResponse.ok(feedbackService.list(issueId));
    }
}
