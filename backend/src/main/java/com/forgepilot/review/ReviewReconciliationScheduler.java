package com.forgepilot.review;

import com.forgepilot.review.ReviewClaimRepository.StalledReview;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 把「已经落库但不再推进」的 Review 重新放回轨道：无人抢占的 PENDING 行，
 * 以及租约已过期的 RUNNING 行。
 *
 * <p>这不是一条兜底分支。实测表明，after-commit 回调里的异常——包括队列满时的
 * {@code TaskRejectedException}——会被 Spring 捕获并记录日志，永远不会到达调用方：
 * webhook 照样答 202，而那条 PENDING 行也已经提交了。如果没有东西去捡起这些行，
 * PENDING 就是一个只有入边、没有出边的状态。所以这不是「出问题了再恢复」，
 * 它是那个状态的**唯一归属者**。ARCHITECTURE.md 3.1、3.2 与 D008 都点了它的名。
 *
 * <p><strong>让它保持诚实的是一条结构性规则：恢复查询的 FROM 子句里
 * 只有 {@code review}，别无他物</strong>
 * （{@link ReviewClaimRepository#findStalled}）。由 {@code review} 驱动的结果集
 * 只可能是**已经存在**的行，因此无论条件写得多错，都不可能把它变成 3.1
 * 明令禁止的补建。这条规则在代码评审时一眼可查，
 * 而“记得别补建”做不到这一点。
 *
 * <p>它也不改变其他任何东西：它找到的行会走同一条抢占路径、同一个执行器，
 * 并获得一个会显示在行上的新 attempt。这里没有重试计数器、
 * 没有备用路径，也没有任何凭空造出的数据。
 */
@Component
public class ReviewReconciliationScheduler {

    private final ReviewClaimRepository claims;
    private final ReviewExecutor executor;
    private final int pendingStallSeconds;
    private final int batchLimit;

    ReviewReconciliationScheduler(ReviewClaimRepository claims, ReviewExecutor executor,
            @Value("${forgepilot.review.pending-stall-seconds}") int pendingStallSeconds,
            @Value("${forgepilot.review.queue-capacity}") int queueCapacity) {
        this.claims = claims;
        this.executor = executor;
        this.pendingStallSeconds = pendingStallSeconds;
        // 单次扫描提交的任务数绝不超过队列的容量。超出的部分只会被线程池拒绝，
        // 白白付出一次扫描却毫无所得：那些行在下一轮扫描时依然是停滞的。
        this.batchLimit = queueCapacity;
    }

    /**
     * **刻意**不加事务。每次抢占各自开启并提交自己的短事务，
     * 从而让每一次的 {@code now()} 都以一个新鲜的参照时间求值——
     * 若放在一个长事务里，批次中越靠后的行就会被拿去和一个越来越陈旧的时钟比较，
     * 于是干脆不再被恢复。
     *
     * <p>首次扫描会完整等待一个间隔，以免启动期间刚交给执行器的 Review
     * 还没来得及跑就被当成停滞。
     */
    @Scheduled(fixedDelayString = "${forgepilot.review.reconciliation-interval-ms}",
            initialDelayString = "${forgepilot.review.reconciliation-interval-ms}")
    public void recover() {
        for (StalledReview stalled : claims.findStalled(pendingStallSeconds, batchLimit)) {
            executor.submit(stalled.getProjectId(), stalled.getReviewId());
        }
    }
}
