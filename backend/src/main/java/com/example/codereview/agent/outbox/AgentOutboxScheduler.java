package com.example.codereview.agent.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The piece that was missing: something in production that actually drains the outbox.
 *
 * <p>Without it {@code publishAvailable} was only ever reached from tests, so an Agent run enqueued
 * its first event and then sat in PENDING forever.
 *
 * <p>Scheduling infrastructure comes from {@code AgentSchedulingConfig}, which is gated on the same
 * flag — test contexts leave it off and are completely unaffected.
 */
@Component
@ConditionalOnProperty(value = "app.agent.scheduling.enabled", havingValue = "true")
public class AgentOutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentOutboxScheduler.class);

    private final AgentOutboxMaintenanceService maintenance;
    private final AgentOutboxPublisher publisher;
    private final int batchSize;

    public AgentOutboxScheduler(
            AgentOutboxMaintenanceService maintenance,
            AgentOutboxPublisher publisher,
            @Value("${app.agent.outbox.batch-size:50}") int batchSize
    ) {
        this.maintenance = maintenance;
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    /**
     * Reclaim first, then publish: an event whose worker died should become eligible again in the
     * same pass rather than waiting a further tick.
     *
     * <p>The whole tick is guarded — a scheduled method that throws is simply not rescheduled by
     * some executors, and losing the drain loop to one transient database error would reproduce the
     * exact failure this class exists to fix.
     */
    @Scheduled(
            fixedDelayString = "${app.agent.outbox.fixed-delay-ms:1000}",
            initialDelayString = "${app.agent.outbox.initial-delay-ms:5000}")
    public void tick() {
        try {
            maintenance.requeueExpiredLeases();
        } catch (RuntimeException ex) {
            log.warn("Outbox lease reclamation failed; will retry next tick", ex);
        }
        try {
            maintenance.failExhausted();
        } catch (RuntimeException ex) {
            log.warn("Outbox retirement of exhausted events failed; will retry next tick", ex);
        }
        try {
            publisher.publishAvailable(batchSize);
        } catch (RuntimeException ex) {
            log.warn("Outbox publish pass failed; will retry next tick", ex);
        }
    }
}
