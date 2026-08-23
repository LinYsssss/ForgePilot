package com.forgepilot.review;

import java.util.Optional;
import java.util.UUID;

import com.forgepilot.common.ApiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Review 的 worker 一侧：把它抢过来、持有它、并把它做完。
 *
 * <p>这里的每一次写入都以 {@code review_id + execution_token +
 * status = RUNNING} 为条件，这正是 ARCHITECTURE.md 3.2 所说的
 * “已过期 worker 的写入影响零行”。它的**四**次写入全部有围栏，
 * 而不只是那个显而易见的——实测表明，没有围栏的 {@code fail} 会让一个已死的
 * attempt 把取代它的那个 attempt 标记为失败；没有围栏的 Finding 插入
 * 会让一个已死 attempt 的产出冒充本轮结果。第四处是由**数据库**而非代码加的围栏，
 * 走的是 {@code finding} 指向 {@code (project_id, id, execution_attempt)}
 * 的复合外键：先在 Java 里检查 attempt 存在一个实测窗口，
 * 在其中检查会通过、而插入随后落到了一个活着的 Review 之下。
 *
 * <p>分析本身归 {@link ReviewPipeline} 所有；本类负责抢占、持有和收尾。
 * 这个拆分同时也让依赖保持单向：流水线从不回调执行器，
 * 因此没有需要用 lazy 代理去打破的 bean 循环，
 * 而状态机也得以在一个方法里读完。
 */
@Service
public class ReviewExecutor {

    /** worker 拥有一个 Review 期间所持有的东西。这四个字段合起来就是那道围栏。 */
    public record Claim(long projectId, long reviewId, int attempt, UUID token) {
    }

    private final ThreadPoolTaskExecutor pool;
    private final ReviewClaimRepository claims;
    private final ReviewPipeline pipeline;
    private final TransactionTemplate ownTransaction;
    private final TransactionTemplate finishingTransaction;
    private final int leaseSeconds;

    ReviewExecutor(@Qualifier("reviewWorkerPool") ThreadPoolTaskExecutor pool, ReviewClaimRepository claims,
            ReviewPipeline pipeline, PlatformTransactionManager transactions,
            @Value("${forgepilot.review.lease-seconds}") int leaseSeconds) {
        this.pool = pool;
        this.claims = claims;
        this.pipeline = pipeline;
        // 抢占与续租各用自己的事务，因为 now() 在事务开始时就被冻结了：
        // 在一个长事务里写下的租约，会按该事务已经运行的时长整体缩短，
        // 而一个被缩短的续租，就是一个可能被抢占的续租。
        // 它们不触碰 review 的任何键列，因此嵌套事务不会阻塞在它自己的调用方上。
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 完成与置失败则加入调用方的事务。一次 Review 的 finding 与它的终态
        // 必须一起提交（design.md 4.4）——这里另开事务，会允许出现
        // 「有报告却没有那个说它已完成的状态」，或者反过来的情形。
        this.finishingTransaction = new TransactionTemplate(transactions);
        this.leaseSeconds = leaseSeconds;
    }

    /**
     * 把一个已落库的 Review 交给线程池。这是工作开始的**唯一**途径，
     * 且只有两个地方会调用它：创建 PENDING 行的那个事务提交之后，
     * 以及 reconciliation。
     */
    public void submit(long projectId, long reviewId) {
        pool.execute(() -> run(projectId, reviewId));
    }

    /**
     * 若这个 Review 可被抢占则抢下它，并在同一个事务里先丢弃被放弃 attempt 的产出。
     * 这两条语句不能拆开：被放弃 attempt 的那些 finding 引用着即将被改动的
     * attempt 编号，因此删除它们是抢占的**前置条件**，而不是抢占之后的顺手整理。
     *
     * <p>返回空是正常答案，不是错误。它意味着别人正持有一个存活的租约，
     * 或者这一行已经 COMPLETED，又或者在本次调用排队期间它已经被抢走了。
     */
    public Optional<Claim> claim(long projectId, long reviewId) {
        return ownTransaction.execute(status -> {
            claims.discardAbandonedFindings(projectId, reviewId);
            UUID token = UUID.randomUUID();
            if (claims.claim(projectId, reviewId, token.toString(), leaseSeconds) != 1) {
                return Optional.empty();
            }
            // 这里回读是安全的，无需 RETURNING：那次更新会把本行的锁一直持有到提交，
            // 因此中间没有任何东西能改动 attempt。
            return Optional.of(new Claim(projectId, reviewId,
                    claims.attemptOf(projectId, reviewId), token));
        });
    }

    /** 返回 false 表示租约已丢失；调用方不再拥有这个 Review。 */
    public boolean renew(Claim claim) {
        return Boolean.TRUE.equals(ownTransaction.execute(status ->
                claims.renew(claim.projectId(), claim.reviewId(), claim.token().toString(), leaseSeconds) == 1));
    }

    public boolean complete(Claim claim) {
        return Boolean.TRUE.equals(finishingTransaction.execute(status ->
                claims.complete(claim.projectId(), claim.reviewId(), claim.token().toString()) == 1));
    }

    public boolean fail(Claim claim) {
        return Boolean.TRUE.equals(finishingTransaction.execute(status ->
                claims.fail(claim.projectId(), claim.reviewId(), claim.token().toString()) == 1));
    }

    /**
     * 线程池任务，同时也是 3.2 那整套执行状态机的集中体现：
     * 抢占它、分析它，然后要么置为失败，要么存下报告并把它完成。
     *
     * <p>它跑在线程池线程上，自身不持有任何事务——正是这一点让 {@link #claim}
     * 得以成为一个在**单个 Review**（而不是单个批次）内部开始并提交的短事务，
     * 也让那些每次最多 120 秒的 provider 调用完全不占用五连接的连接池。
     *
     * <p>抢不到，对于一个别人已经持有的 Review 来说是正常答案，
     * 因此它什么都不做，也什么都不说。
     */
    void run(long projectId, long reviewId) {
        Optional<Claim> taken = claim(projectId, reviewId);
        if (taken.isEmpty()) {
            return;
        }
        Claim claim = taken.get();

        Optional<ReviewPipeline.Report> report;
        try {
            report = pipeline.analyse(claim, () -> renew(claim));
        } catch (ApiException providerFailure) {
            // 3.2：“RUNNING -> FAILED：AI 失败”。让这个异常逃逸出去，会让该行
            // 一直停在 RUNNING 直到租约过期，而 reconciliation 随后会重新抢占它、
            // 再次去调用那个同样不可达的 provider——如此循环，永无止境。
            // FAILED 会停下来等人处理，这正是 3.2 里人工重试的用途。
            fail(claim);
            return;
        }
        if (report.isEmpty()) {
            // 某个批次即便用掉那一次修复也仍然解析不了，或者综合阶段没能通过校验。
            // 什么都不写：3.4.4 禁止残缺报告，3.5 禁止“成功的空结果”。
            fail(claim);
            return;
        }

        finishingTransaction.executeWithoutResult(status -> {
            pipeline.store(claim, report.get());
            if (!complete(claim)) {
                // 租约在分析与本次写入之间丢失了。报告不得比它所属的 Review 活得更久：
                // 这个 attempt 已不再拥有那一行，而另一个 attempt 正在产出它自己的结果。
                status.setRollbackOnly();
            }
        });
    }
}
