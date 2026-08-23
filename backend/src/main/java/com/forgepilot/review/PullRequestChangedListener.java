package com.forgepilot.review;

import com.forgepilot.review.ReviewService.ReviewReady;
import com.forgepilot.scm.PullRequestChanged;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 自动触发的两半。它们是两个方法，这不是风格选择：实测表明，
 * 一个同时挂着 {@code @EventListener} 与
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 的方法，
 * 只会在**提交之后**被调用<em>一次</em>。事务型工厂胜出，
 * 朴素适配器根本不会被创建，而容器既不警告也不报错——
 * 于是「事务内」那一半悄然消失，同时所有单线程测试依然全绿。
 * 随之一起消失的，是「监听器失败则 SCM 事务回滚」这条保证。
 */
@Component
public class PullRequestChangedListener {

    private final ReviewService reviews;
    private final ReviewExecutor executor;

    PullRequestChangedListener(ReviewService reviews, ReviewExecutor executor) {
        this.reviews = reviews;
        this.executor = executor;
    }

    /**
     * 加入那个更新了 PR 的事务，并为它当前的四元组幂等地创建或接管 Review。
     *
     * <p>这里抛异常本就应该让整次入库致命失败：绝不能存在这样一种已提交状态
     * ——PR 已经推进，而它所隐含的 Review 却缺席（ARCHITECTURE.md 3.1）。
     * 正是出于这个理由，这里什么都不捕获。
     */
    @EventListener
    public void openTheReviewInTheSameTransaction(PullRequestChanged event) {
        reviews.openForDelivery(event.pullRequestId());
    }

    /**
     * 把已提交的那一行交给执行器，此外什么都不做。
     *
     * <p>本方法中的任何代码都不得触碰数据库。实测：在这个时点
     * {@code isActualTransactionActive()} 仍返回 true，
     * {@code isConnectionTransactional()} 也仍返回 true，
     * 而物理连接的 autoCommit 其实已经被恢复了——于是
     * {@code EntityManager.persist} 会直接以 “No active transaction” 失败
     * （这反而是好结果），而一句裸的 {@code JdbcTemplate.update} 会悄无声息地
     * <em>成功</em>，在一个任何东西都回滚不了的连接上提交了一条语句。
     * 后者才是危险的情况，恰恰因为它看上去像是成功了。
     * 如果这里将来确实需要写库，那必须用 {@code REQUIRES_NEW}；
     * 而且绝不能通过询问 {@code TransactionSynchronizationManager} 来判断阶段
     * ——实测它给的答案是错的。
     *
     * <p>这里同样不得有任何阻塞：webhook 的 202 会一直等本方法返回，
     * 实测下来几乎是一比一地被拖长。
     *
     * <p>用 {@link ReviewReady} 而非 {@code PullRequestChanged}，
     * 是为了让这里无需再查一次就知道该跑哪一行——也是为了让人工触发与重试
     * （它们在各自的事务内部发布同一个信号）经由这一条路径抵达执行器，
     * 而不是另开第二条。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handOffOnceTheRowIsCommitted(ReviewReady event) {
        executor.submit(event.projectId(), event.reviewId());
    }
}
