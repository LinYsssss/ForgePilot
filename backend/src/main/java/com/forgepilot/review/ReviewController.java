package com.forgepilot.review;

import java.security.Principal;
import java.util.List;

import com.forgepilot.auth.AccountView;
import com.forgepilot.auth.UserDirectory;
import com.forgepilot.common.ApiException;
import com.forgepilot.review.ReviewViews.DecisionResult;
import com.forgepilot.review.ReviewViews.ProjectReviewRow;
import com.forgepilot.review.ReviewViews.ReviewDetail;
import com.forgepilot.review.ReviewViews.ReviewSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Requesting a Review, reading it and deciding it (api-contract.md 2.1, 2.2, 2.3, 2.4). */
@RestController
@RequestMapping("/api/projects/{projectId}")
class ReviewController {

    private final ReviewService reviews;
    private final ReviewDecisionService decisions;
    private final UserDirectory users;

    ReviewController(ReviewService reviews, ReviewDecisionService decisions, UserDirectory users) {
        this.reviews = reviews;
        this.decisions = decisions;
        this.users = users;
    }

    /**
     * api-contract.md 2.1. Trigger, re-trigger after a new requirement revision and
     * retry after a failure are one endpoint over one service path, because they
     * are one thing: which of them it turns out to be depends only on what already
     * exists under the pull request's current identity (ARCHITECTURE.md 3.1).
     *
     * <p>202 rather than 201: the row may be brand new, reused after a failure, or
     * the one already running, and in every case the answer is "accepted, not
     * finished". A COMPLETED Review answers 409 from the service — 3.2 never re-runs
     * one.
     *
     * <p>Nothing here hands anything to the executor, deliberately. {@code
     * ReviewService} publishes {@code ReviewReady} inside its own transaction and
     * {@code PullRequestChangedListener} submits after that transaction commits, so
     * a newly created or retried row is already queued by the time this returns.
     * Submitting again here would enqueue it twice, and for the idempotent
     * PENDING/RUNNING answer it would enqueue a Review the contract says must
     * <em>not</em> be re-queued.
     */
    @PostMapping("/pull-requests/{pullRequestId}/reviews")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ReviewRequested request(@PathVariable long projectId, @PathVariable long pullRequestId,
            Principal principal) {
        Review review = reviews.requestReview(projectId, pullRequestId, userIdOf(principal));
        return new ReviewRequested(review.getId(), review.getStatus(), review.getExecutionAttempt());
    }

    /** The 代码审查 page's list. Project scoped, newest first, no paging in the MVP. */
    @GetMapping("/reviews")
    List<ProjectReviewRow> list(@PathVariable long projectId, Principal principal) {
        return decisions.listForProject(projectId, userIdOf(principal));
    }

    @GetMapping("/reviews/{reviewId}")
    ReviewDetail get(@PathVariable long projectId, @PathVariable long reviewId, Principal principal) {
        return decisions.detail(projectId, userIdOf(principal), reviewId);
    }

    @GetMapping("/pull-requests/{pullRequestId}/reviews")
    List<ReviewSummary> history(@PathVariable long projectId, @PathVariable long pullRequestId,
            Principal principal) {
        return decisions.history(projectId, userIdOf(principal), pullRequestId);
    }

    /**
     * The one-shot verdict. POST rather than PUT or PATCH: it may be written once
     * and never rewritten, so it is not an idempotent replacement of anything.
     */
    @PostMapping("/reviews/{reviewId}/decision")
    DecisionResult decide(@PathVariable long projectId, @PathVariable long reviewId,
            @Valid @RequestBody DecisionRequest request, Principal principal) {
        return decisions.decide(projectId, userIdOf(principal), reviewId, request.decision(),
                request.comment());
    }

    /** Resolved here, in the controller: business services never see Spring Security (D013.6). */
    private long userIdOf(Principal principal) {
        return users.byUsername(principal.getName()).map(AccountView::id)
                .orElseThrow(ApiException::notFound);
    }

    /**
     * api-contract.md 2.1's body. {@code executionAttempt} is returned because it
     * is the only way a caller can tell a retry apart from an idempotent answer:
     * both come back 202 on the same row id.
     */
    record ReviewRequested(long reviewId, ReviewStatus status, int executionAttempt) {
    }

    /**
     * {@code PENDING} is accepted by the enum and refused by the service, because
     * the value set of the column and the value set of a human decision are not the
     * same thing — {@code PENDING} is the absence of one.
     */
    record DecisionRequest(@NotNull ReviewDecision decision, @Size(max = 2000) String comment) {
    }
}
