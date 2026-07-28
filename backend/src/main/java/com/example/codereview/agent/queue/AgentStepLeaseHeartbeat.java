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
 * Keeps a step's lease alive for as long as the worker is actually working on it.
 *
 * <p>A fixed lease with no renewal forces an awkward choice: make it long enough for the slowest
 * possible step and a crashed worker strands the run for that long, or make it short and the
 * watchdog starts reclaiming steps that are merely slow. Renewing on a heartbeat decouples the two
 * — the lease only lapses when the worker genuinely stops.
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

    /** Runs {@code work} with the lease on {@code stepId} being renewed in the background. */
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
                // Somebody else owns the step now, or it already finished. Nothing to renew; the
                // completion CAS will discard this worker's result when it eventually returns.
                log.warn("Agent step {} is no longer held by this worker; stopping lease renewal", stepId);
            }
        } catch (RuntimeException ex) {
            // A failed heartbeat must not kill the executing step: the lease still has time left,
            // and the next tick may well succeed.
            log.warn("Failed to renew execution lease for Agent step {}", stepId, ex);
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
