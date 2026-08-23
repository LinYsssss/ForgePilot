package com.forgepilot.review;

import java.util.Set;

import com.forgepilot.scm.PullRequestDecisionGate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PullRequestDecisionGate} 的实现。它住在 {@code review} 里，
 * 因为 Decision 是 {@code review} 的事实；{@code scm} 只拿到一个布尔答案，
 * 拿不到 Review、Finding 或任何编排入口。
 *
 * <p>每次都现算，绝不在 {@code pull_request} 行上缓存标志位：force-push
 * 回退到某个较旧的 head 时，那个 head 上的旧裁定必须自动重新生效，
 * 而一个存下来的布尔值做不到——这与 {@code ReviewDecisionService} 推导
 * Decision Gate 用的是同一条理由。
 */
@Component
class ReviewDecisionGate implements PullRequestDecisionGate {

    /** ARCHITECTURE.md 3.1：终局裁定只有这两个取值，PENDING 不是裁定。 */
    private static final Set<ReviewDecision> FINAL =
            Set.of(ReviewDecision.APPROVE, ReviewDecision.REQUEST_CHANGES);

    private final ReviewRepository reviews;

    ReviewDecisionGate(ReviewRepository reviews) {
        this.reviews = reviews;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasFinalDecisionOnHead(long projectId, long pullRequestId, String headSha) {
        return reviews.existsByProjectIdAndPullRequestIdAndHeadShaAndDecisionIn(
                projectId, pullRequestId, headSha, FINAL);
    }
}
