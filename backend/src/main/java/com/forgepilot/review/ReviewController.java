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

/** 发起 Review、读取 Review，以及对它作出决策（api-contract.md 2.1、2.2、2.3、2.4）。 */
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
     * api-contract.md 2.1。首次触发、新需求修订后的重新触发、失败后的重试，
     * 共用一个端点、一条服务路径，因为它们本就是同一件事：
     * 最终落到哪一种，只取决于该 PR 当前身份之下已经存在什么
     * （ARCHITECTURE.md 3.1）。
     *
     * <p>返回 202 而非 201：这一行可能是全新的、失败后复用的，
     * 也可能就是那个正在运行的，无论哪种情况，答案都是「已接受，尚未完成」。
     * 已 COMPLETED 的 Review 会由服务层答 409——3.2 规定它绝不重跑。
     *
     * <p>这里**刻意**不向执行器交付任何东西。{@code ReviewService} 会在它自己的
     * 事务内部发布 {@code ReviewReady}，而 {@code PullRequestChangedListener}
     * 在该事务提交之后才提交任务，因此新建或重试的行在本方法返回时就已经排上队了。
     * 在这里再提交一次会让它入队两次；而对于幂等的 PENDING/RUNNING 答案，
     * 那更是把一个契约明确规定<em>不得</em>重新入队的 Review 又塞了进去。
     */
    @PostMapping("/pull-requests/{pullRequestId}/reviews")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ReviewRequested request(@PathVariable long projectId, @PathVariable long pullRequestId,
            Principal principal) {
        Review review = reviews.requestReview(projectId, pullRequestId, userIdOf(principal));
        return new ReviewRequested(review.getId(), review.getStatus(), review.getExecutionAttempt());
    }

    /** 代码审查页面的列表。限定项目内、最新在前，MVP 阶段不做分页。 */
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
     * 一次性裁定。用 POST 而非 PUT/PATCH：它只能写一次且永不改写，
     * 因此它并不是对任何东西的幂等替换。
     */
    @PostMapping("/reviews/{reviewId}/decision")
    DecisionResult decide(@PathVariable long projectId, @PathVariable long reviewId,
            @Valid @RequestBody DecisionRequest request, Principal principal) {
        return decisions.decide(projectId, userIdOf(principal), reviewId, request.decision(),
                request.comment());
    }

    /** 在这里——控制器层——完成解析：业务服务永远看不到 Spring Security（D013.6）。 */
    private long userIdOf(Principal principal) {
        return users.byUsername(principal.getName()).map(AccountView::id)
                .orElseThrow(ApiException::notFound);
    }

    /**
     * api-contract.md 2.1 的响应体。之所以返回 {@code executionAttempt}，
     * 是因为它是调用方区分「一次重试」与「一次幂等答复」的唯一手段：
     * 两者都会在同一个行 id 上返回 202。
     */
    record ReviewRequested(long reviewId, ReviewStatus status, int executionAttempt) {
    }

    /**
     * {@code PENDING} 能被枚举接受、却会被服务层拒绝，因为「列的值域」与
     * 「一次人工决策的值域」并不是一回事——{@code PENDING} 恰恰表示
     * 「还没有作出决策」。
     */
    record DecisionRequest(@NotNull ReviewDecision decision, @Size(max = 2000) String comment) {
    }
}
