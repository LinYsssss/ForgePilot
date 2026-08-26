package com.forgepilot.review;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 运行 Review 的线程池，以及 reconciliation 所依赖的调度器。
 *
 * <p><strong>并发上限设在 {@code corePoolSize} 上，且 {@code queueCapacity}
 * 是显式设置的，这两点都是承重的。</strong>
 * {@code ThreadPoolExecutor} 只有在队列<em>满了</em>之后才会超出 core size 扩容，
 * 而默认队列是无界的，于是它永远填不满、线程池也就永远不会扩容。
 * 实测：{@code core=1 / max=4} 搭配默认队列时，在有八个任务待处理的情况下
 * 始终只有一个线程。因此只写 {@code setMaxPoolSize(n)} 看上去是把上限设成了 n，
 * 实际上上限是 {@code corePoolSize}；而任何只盯着“Review 跑完了没”的测试
 * 都会在这种配置下通过。{@code ReviewEngineTest} 正是为此直接断言底层执行器的
 * core size。
 *
 * <p>默认上限被冻结为 2，依据是 ARCHITECTURE.md 7.2 与
 * 在 4.10 GB 主机上做的最大预算实测。两个并发的 300 文件 Review、
 * 各自带着 3,989,101 字符的规范清单，在生产 JVM 与 PostgreSQL 上限下
 * 完成，且没有出现截断、重启、OOM 或 Hikari 等待。Hikari 的五个连接
 * 仍是硬天花板，为 Web 层留出了余量；更紧张的部署可以把上限覆盖为 1。
 *
 * <p>只要声明了任何 {@code Executor} bean，Boot 就会彻底停止创建
 * {@code applicationTaskExecutor}（实测：
 * {@code applicationTaskExecutorPresent=false}）。ForgePilot 不使用 MVC 异步
 * ——没有 {@code Callable}、没有 {@code DeferredResult}、没有 SSE——
 * 因此这在今天不花任何代价。但等到有人加上 SSE、却发现异步请求跑在一个
 * 没人为它们配置过的池子上时，它就要花代价了。写一段注释比那个意外便宜；
 * 而为一个明确不做的功能提前写变通方案，则不便宜。
 *
 * <p>{@code @EnableScheduling} 放在这里，是因为否则整个上下文里没有调度器
 * ——实测在加上它之前 {@code TaskScheduler} bean 的数量为零。它来自
 * {@code spring-context}，而后者本来就在 classpath 上，因此 pom 不需要改动。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ReviewExecutorConfig {

    @Bean
    ThreadPoolTaskExecutor reviewWorkerPool(
            @Value("${forgepilot.review.concurrency}") int concurrency,
            @Value("${forgepilot.review.queue-capacity}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        // 有意与 core size 相等。在有界队列下，更大的值根本不可达，
        // 读起来还会产生误导。
        executor.setMaxPoolSize(concurrency);
        // 有界，因此积压会被拒绝而不是被堆积。实测：默认拒绝策略以
        // TaskRejectedException 中止，而在 after-commit 回调里这个异常会被
        // Spring 吞掉——于是队列满时会留下一条已提交、却没有 worker 的
        // PENDING 行，而这恰好就是 reconciliation 存在所要恢复的那个状态。
        // 改成无界，等于用它换来一台 4 GB 机器上的无界积压。
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("review-");
        return executor;
    }
}
