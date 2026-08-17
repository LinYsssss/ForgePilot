package com.example.codereview.pullrequest;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import com.example.codereview.pullrequest.PullRequestDtos.CreatePullRequestRequest;
import com.example.codereview.pullrequest.PullRequestDtos.CreatePullRequestReviewTaskRequest;
import com.example.codereview.pullrequest.PullRequestDtos.PullRequestResponse;
import com.example.codereview.pullrequest.PullRequestDtos.ReviewActionRequest;
import com.example.codereview.pullrequest.PullRequestDtos.ReviewActionResponse;
import com.example.codereview.pullrequest.PullRequestDtos.UpdatePullRequestRequest;
import com.example.codereview.review.ReviewDtos.CreateReviewTaskRequest;
import com.example.codereview.review.ReviewDtos.ReviewTaskResponse;
import com.example.codereview.review.ReviewService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/pull-requests")
public class PullRequestController {

    private final PullRequestService pullRequestService;
    private final ReviewService reviewService;
    private final CurrentUserProvider currentUserProvider;

    public PullRequestController(PullRequestService pullRequestService, ReviewService reviewService,
                                 CurrentUserProvider currentUserProvider) {
        this.pullRequestService = pullRequestService;
        this.reviewService = reviewService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public ApiResponse<PullRequestResponse> create(@PathVariable Long projectId,
                                                   @Valid @RequestBody CreatePullRequestRequest request) {
        return ApiResponse.ok(pullRequestService.create(projectId, currentUserProvider.getRequired().userId(), request));
    }

    @GetMapping
    public ApiResponse<List<PullRequestResponse>> list(@PathVariable Long projectId) {
        return ApiResponse.ok(pullRequestService.list(projectId, currentUserProvider.getRequired().userId()));
    }

    @GetMapping("/{pullRequestId}")
    public ApiResponse<PullRequestResponse> detail(@PathVariable Long projectId, @PathVariable Long pullRequestId) {
        return ApiResponse.ok(pullRequestService.detail(projectId, currentUserProvider.getRequired().userId(), pullRequestId));
    }

    @PutMapping("/{pullRequestId}")
    public ApiResponse<PullRequestResponse> update(@PathVariable Long projectId, @PathVariable Long pullRequestId,
                                                   @Valid @RequestBody UpdatePullRequestRequest request) {
        return ApiResponse.ok(pullRequestService.update(projectId, currentUserProvider.getRequired().userId(), pullRequestId, request));
    }

    @PostMapping("/{pullRequestId}/review-task")
    public ApiResponse<ReviewTaskResponse> createReviewTask(@PathVariable Long projectId, @PathVariable Long pullRequestId,
                                                            @RequestBody(required = false) CreatePullRequestReviewTaskRequest request) {
        List<Long> documentIds = request == null ? null : request.documentIds();
        CreateReviewTaskRequest reviewRequest = new CreateReviewTaskRequest(null, null, null, documentIds, pullRequestId, null);
        return ApiResponse.ok(reviewService.create(projectId, currentUserProvider.getRequired().userId(), reviewRequest));
    }

    @PostMapping("/{pullRequestId}/actions")
    public ApiResponse<ReviewActionResponse> createAction(@PathVariable Long projectId, @PathVariable Long pullRequestId,
                                                          @Valid @RequestBody ReviewActionRequest request) {
        return ApiResponse.ok(pullRequestService.createAction(projectId, currentUserProvider.getRequired().userId(), pullRequestId, request));
    }

    @GetMapping("/{pullRequestId}/actions")
    public ApiResponse<List<ReviewActionResponse>> actions(@PathVariable Long projectId, @PathVariable Long pullRequestId) {
        return ApiResponse.ok(pullRequestService.actions(projectId, currentUserProvider.getRequired().userId(), pullRequestId));
    }
}
