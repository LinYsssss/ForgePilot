package com.example.codereview.agent.queue;

import com.example.codereview.agent.run.AgentStepRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 只要 worker 还在真干活,就持续为该步骤的租约续期。
 *
 * <p>固定租约且不续期会逼出一个两难:租约长到够最慢的步骤用,那么 worker 一崩,run 就被搁置那么久;
 * 租约短了,watchdog 又会开始回收那些只是**慢**而已的步骤。靠心跳续期把两者解耦——
 * 只有 worker 真的停了,租约才会失效。
 */
@Component
public class AgentStepLeaseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(AgentStepLeaseHeartbeat.class);

    private final AgentStepRepository steps;
    private final Clock clock;
    private final Duration interval;
    private final Duration leaseDuration;
    private final ScheduledExecutorService scheduler;

    @Autowired
    public AgentStepLeaseHeartbeat(
            AgentStepRepository steps,
            @Value("${app.agent.step.heartbeat-interval:45s}") Duration interval,
            @Value("${app.agent.step.lease-duration:3m}") Duration leaseDuration
    ) {
        this(steps, Clock.systemUTC(), interval, leaseDuration);
    }

    public AgentStepLeaseHeartbeat(
            AgentStepRepository steps, Clock clock, Duration interval, Duration leaseDuration) {
        this.steps = steps;
        this.clock = clock;
        this.interval = interval;
        this.leaseDuration = leaseDuration;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-step-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 执行 {@code work},期间在后台为 {@code stepId} 的租约持续续期。 */
    public <T> T runWithRenewal(Long stepId, String executionToken, Supplier<T> work) {
        long periodMillis = Math.max(1_000L, interval.toMillis());
        ScheduledFuture<?> renewal = scheduler.scheduleWithFixedDelay(
                () -> renew(stepId, executionToken), periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        try {
            return work.get();
        } finally {
            renewal.cancel(true);
        }
    }

    private void renew(Long stepId, String executionToken) {
        try {
            Instant now = clock.instant();
            if (steps.renewLease(stepId, executionToken, now.plus(leaseDuration), now) == 0) {
                // 步骤现在归别人了,或者它已经结束。没什么可续的;等这个 worker 最终返回时,
                // 完成阶段的 CAS 会把它的结果丢弃。
                log.warn("Agent step {} is no longer held by this worker; stopping lease renewal", stepId);
            }
        } catch (RuntimeException ex) {
            // 一次心跳失败不能把正在执行的步骤弄死:租约还有剩余时间,下一个 tick 很可能就成功了。
            log.warn("Failed to renew execution lease for Agent step {}", stepId, ex);
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
