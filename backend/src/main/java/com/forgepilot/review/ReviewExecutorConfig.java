package com.forgepilot.review;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The pool that runs Reviews, and the scheduler that reconciliation runs on.
 *
 * <p><strong>The concurrency limit is on {@code corePoolSize}, and
 * {@code queueCapacity} is set explicitly, and both of those are load-bearing.</strong>
 * A {@code ThreadPoolExecutor} only grows past its core size once the queue is
 * <em>full</em>, and the default queue is unbounded, so it never fills and the
 * pool never grows. Measured: {@code core=1 / max=4} with the default queue kept
 * exactly one thread while eight tasks were pending. Writing only
 * {@code setMaxPoolSize(n)} therefore looks like a limit of n and is really a
 * limit of {@code corePoolSize}, and any test that merely watches a Review finish
 * passes against it. {@code ReviewEngineTest} asserts the core size of the
 * underlying executor directly for that reason.
 *
 * <p>The default limit is frozen at 2 from the Phase 6 maximum-budget measurement
 * on a 4.10 GB host required by ARCHITECTURE.md 7.2 and D012. Two concurrent
 * 300-file Reviews, each with a 3,989,101-character canonical manifest, completed
 * without truncation, restart, OOM or Hikari wait under the production JVM and
 * PostgreSQL limits. Hikari's five connections remain the hard ceiling, leaving
 * capacity for the web tier; a tighter deployment can override the limit to 1.
 *
 * <p>Declaring any {@code Executor} bean makes Boot stop creating
 * {@code applicationTaskExecutor} altogether (measured:
 * {@code applicationTaskExecutorPresent=false}). ForgePilot uses no MVC async —
 * no {@code Callable}, no {@code DeferredResult}, no SSE — so this costs nothing
 * today. It will cost something the day somebody adds SSE and finds async
 * requests running on a pool nobody configured for them. A comment is cheaper
 * than that surprise; a workaround for a feature this batch explicitly does not
 * build would not be.
 *
 * <p>{@code @EnableScheduling} lives here because the context has no scheduler
 * otherwise — measured, zero {@code TaskScheduler} beans before it was added. It
 * comes from {@code spring-context}, which is already on the classpath, so the
 * pom does not change.
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
        // Equal to the core size on purpose. Anything larger would be unreachable
        // with a bounded queue and misleading to read.
        executor.setMaxPoolSize(concurrency);
        // Bounded, so a backlog is rejected rather than accumulated. Measured, the
        // default rejection policy aborts with TaskRejectedException, and in an
        // after-commit callback that exception is swallowed by Spring — so a full
        // queue leaves a committed PENDING row with no worker, which is precisely
        // the state reconciliation exists to recover. Unbounded would trade that
        // for an unbounded backlog on a 4 GB machine.
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("review-");
        return executor;
    }
}
