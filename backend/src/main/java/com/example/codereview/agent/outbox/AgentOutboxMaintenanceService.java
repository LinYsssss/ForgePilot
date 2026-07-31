package com.example.codereview.agent.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Housekeeping that runs before each publish pass: reclaim events whose holder died, and retire the
 * ones that have exhausted their retries.
 *
 * <p>Kept apart from {@link AgentOutboxPublisher} because these are pure short transactions with no
 * broker involvement, and because a failure here must not stop publishing.
 */
@Service
public class AgentOutboxMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(AgentOutboxMaintenanceService.class);
    private static final String LEASE_EXPIRED_ERROR = "claim lease expired before the publish completed";

    private final AgentOutboxRepository repository;
    private final Clock clock;
    private final Duration retryDelay;
    private final int maxAttempts;

    @Autowired
    public AgentOutboxMaintenanceService(
            AgentOutboxRepository repository,
            @Value("${app.agent.outbox.retry-delay:10s}") Duration retryDelay,
            @Value("${app.agent.outbox.max-attempts:8}") int maxAttempts
    ) {
        this(repository, Clock.systemUTC(), retryDelay, maxAttempts);
    }

    public AgentOutboxMaintenanceService(
            AgentOutboxRepository repository, Clock clock, Duration retryDelay, int maxAttempts) {
        this.repository = repository;
        this.clock = clock;
        this.retryDelay = retryDelay;
        this.maxAttempts = maxAttempts;
    }

    /** @return how many stuck events were handed back to the pending pool. */
    public int requeueExpiredLeases() {
        Instant now = clock.instant();
        int requeued = repository.requeueExpiredLeases(now, now.plus(retryDelay), LEASE_EXPIRED_ERROR);
        if (requeued > 0) {
            log.warn("Requeued {} Agent outbox event(s) whose claim lease had expired", requeued);
        }
        return requeued;
    }

    /** @return how many events were moved to the terminal FAILED state. */
    public int failExhausted() {
        int failed = repository.failExhausted(clock.instant(), maxAttempts);
        if (failed > 0) {
            log.error("Gave up on {} Agent outbox event(s) after {} attempts; operator action needed",
                    failed, maxAttempts);
        }
        return failed;
    }
}
