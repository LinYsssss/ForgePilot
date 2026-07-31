package com.example.codereview.agent.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.config.RabbitMqConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false"
})
@ActiveProfiles("dev")
class AgentOutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-06-19T00:00:00Z");
    private static final Duration RETRY_DELAY = Duration.ofSeconds(10);
    private static final Duration LEASE = Duration.ofSeconds(60);

    @Autowired
    private AgentRunRepository runs;

    @Autowired
    private AgentOutboxRepository outbox;

    @Autowired
    private AgentRunTransitionService transitions;

    @MockitoBean
    private RabbitTemplate rabbit;

    private AgentOutboxPublisher publisher;
    private AgentOutboxMaintenanceService maintenance;

    @BeforeEach
    void setUp() {
        outbox.deleteAll();
        runs.deleteAll();
        reset(rabbit);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        publisher = new AgentOutboxPublisher(outbox, rabbit, clock, RETRY_DELAY, 1_000, LEASE, Duration.ofSeconds(10));
        maintenance = new AgentOutboxMaintenanceService(outbox, clock, RETRY_DELAY, 8);
    }

    // ------------------------------------------------------------------ transactional enqueue

    @Test
    void transitionAndOutboxInsertCommitTogether() {
        AgentRun run = runs.save(new AgentRun(1L, 2L, 3L, "trigger-commit", "abc"));

        transitions.transitionAndEnqueue(
                run.getId(),
                AgentRunStatus.PREPARING_REPOSITORY,
                1,
                "run:" + run.getId() + ":step:1:attempt:0",
                "{\"agentRunId\":" + run.getId() + ",\"sequenceNo\":1,\"attempt\":0}",
                "trace-1"
        );

        assertThat(runs.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.PREPARING_REPOSITORY);
        assertThat(outbox.findAll()).singleElement()
                .extracting(AgentOutboxEvent::getStatus)
                .isEqualTo(AgentOutboxStatus.PENDING);
    }

    @Test
    void duplicateOutboxKeyRollsBackStateTransition() {
        AgentRun run = runs.save(new AgentRun(1L, 2L, 3L, "trigger-rollback", "abc"));
        String eventKey = "duplicate-key";
        outbox.saveAndFlush(AgentOutboxEvent.pending(eventKey, run.getId(), "AGENT_STEP", "{}", "trace-1", NOW));

        assertThatThrownBy(() -> transitions.transitionAndEnqueue(
                run.getId(), AgentRunStatus.PREPARING_REPOSITORY, 1, eventKey, "{}", "trace-1"
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(AgentRunStatus.RECEIVED);
        assertThat(outbox.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ publishing

    @Test
    void mqFailureLeavesPendingEventWithRetryMetadata() {
        AgentOutboxEvent event = save("failure-key", "{\"value\":1}");
        doThrow(new IllegalStateException("broker unavailable"))
                .when(rabbit)
                .convertAndSend(
                        eq(RabbitMqConfig.AGENT_EXCHANGE),
                        eq(RabbitMqConfig.AGENT_STEP_ROUTING_KEY),
                        eq("{\"value\":1}"),
                        any(CorrelationData.class));

        publisher.publishAvailable(10);

        AgentOutboxEvent failed = outbox.findById(event.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(AgentOutboxStatus.PENDING);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getNextAttemptAt()).isEqualTo(NOW.plus(RETRY_DELAY));
        assertThat(failed.getLastError()).contains("broker unavailable");
        // A failed publish must not leave the claim behind, or the reaper would have to clean up
        // after every ordinary retry.
        assertThat(failed.getClaimToken()).isNull();
        assertThat(failed.getLeaseExpiresAt()).isNull();
    }

    @Test
    void sentEventIsNotPublishedAgain() {
        save("sent-once", "{\"value\":2}");

        publisher.publishAvailable(10);
        publisher.publishAvailable(10);

        verify(rabbit, times(1)).convertAndSend(
                eq(RabbitMqConfig.AGENT_EXCHANGE),
                eq(RabbitMqConfig.AGENT_STEP_ROUTING_KEY),
                eq("{\"value\":2}"),
                any(CorrelationData.class));
        assertThat(outbox.findAll()).singleElement()
                .extracting(AgentOutboxEvent::getStatus)
                .isEqualTo(AgentOutboxStatus.SENT);
    }

    @Test
    void unsupportedEventTypeIsRetriedRatherThanMarkedSent() {
        AgentOutboxEvent event = outbox.save(AgentOutboxEvent.pending(
                "bad-type", 1L, "TOTALLY_UNKNOWN", "{}", "trace-bad", NOW));

        publisher.publishAvailable(10);

        AgentOutboxEvent stored = outbox.findById(event.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AgentOutboxStatus.PENDING);
        assertThat(stored.getLastError()).contains("Unsupported Agent outbox event type");
    }

    // ------------------------------------------------------------------ claim, lease, CAS

    @Test
    void atomicClaimAllowsOnlyOnePublisher() {
        AgentOutboxEvent event = save("claim-once", "{}");

        assertThat(outbox.claim(event.getId(), NOW, "token-a", NOW.plus(LEASE))).isEqualTo(1);
        assertThat(outbox.claim(event.getId(), NOW, "token-b", NOW.plus(LEASE))).isZero();

        AgentOutboxEvent claimed = outbox.findById(event.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(AgentOutboxStatus.PROCESSING);
        assertThat(claimed.getClaimToken()).isEqualTo("token-a");
        assertThat(claimed.getLeaseExpiresAt()).isEqualTo(NOW.plus(LEASE));
    }

    @Test
    void staleClaimTokenCannotCompleteSomebodyElsesLease() {
        AgentOutboxEvent event = save("stale-token", "{}");
        outbox.claim(event.getId(), NOW, "current-token", NOW.plus(LEASE));

        assertThat(outbox.markSent(event.getId(), "old-token", NOW)).isZero();
        assertThat(outbox.markRetry(event.getId(), "old-token", NOW, NOW.plus(RETRY_DELAY), "boom")).isZero();

        // The rightful holder is untouched by the impostor's write-backs.
        assertThat(outbox.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentOutboxStatus.PROCESSING);
        assertThat(outbox.markSent(event.getId(), "current-token", NOW)).isEqualTo(1);
    }

    @Test
    void expiredLeaseIsRequeuedSoACrashedWorkerDoesNotStrandTheEvent() {
        AgentOutboxEvent event = save("expired-lease", "{}");
        // Claim with a lease that has already run out, standing in for a worker that died.
        outbox.claim(event.getId(), NOW, "dead-worker", NOW.minusSeconds(1));

        assertThat(maintenance.requeueExpiredLeases()).isEqualTo(1);

        AgentOutboxEvent requeued = outbox.findById(event.getId()).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(AgentOutboxStatus.PENDING);
        assertThat(requeued.getClaimToken()).isNull();
        assertThat(requeued.getAttemptCount()).isEqualTo(1);
        assertThat(requeued.getLastError()).contains("lease expired");
    }

    @Test
    void liveLeaseIsLeftAlone() {
        AgentOutboxEvent event = save("live-lease", "{}");
        outbox.claim(event.getId(), NOW, "busy-worker", NOW.plus(LEASE));

        assertThat(maintenance.requeueExpiredLeases()).isZero();
        assertThat(outbox.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentOutboxStatus.PROCESSING);
    }

    @Test
    void lateWorkerCannotOverwriteTheResultOfWhoeverReclaimedTheEvent() {
        AgentOutboxEvent event = save("reclaimed", "{}");
        outbox.claim(event.getId(), NOW, "slow-worker", NOW.minusSeconds(1));
        maintenance.requeueExpiredLeases();

        // A requeued event observes the retry backoff, so it is not immediately claimable again.
        assertThat(outbox.claim(event.getId(), NOW, "too-eager", NOW.plus(LEASE))).isZero();

        Instant afterBackoff = NOW.plus(RETRY_DELAY);
        assertThat(outbox.claim(event.getId(), afterBackoff, "new-worker", afterBackoff.plus(LEASE))).isEqualTo(1);
        assertThat(outbox.markSent(event.getId(), "new-worker", afterBackoff)).isEqualTo(1);

        // The original worker finally comes back and reports success.
        assertThat(outbox.markSent(event.getId(), "slow-worker", afterBackoff)).isZero();
        assertThat(outbox.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(AgentOutboxStatus.SENT);
    }

    // ------------------------------------------------------------------ terminal failure

    @Test
    void eventsAreRetiredOnceRetriesAreExhausted() {
        AgentOutboxEvent event = save("exhausted", "{}");
        for (int i = 0; i < 8; i++) {
            outbox.claim(event.getId(), NOW, "t" + i, NOW.plus(LEASE));
            outbox.markRetry(event.getId(), "t" + i, NOW, NOW, "still failing");
        }

        assertThat(maintenance.failExhausted()).isEqualTo(1);

        AgentOutboxEvent failed = outbox.findById(event.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(AgentOutboxStatus.FAILED);
        assertThat(failed.getFailedAt()).isEqualTo(NOW);
        // Terminal means terminal: it must never be picked up again.
        assertThat(outbox.findAvailableIds(NOW, org.springframework.data.domain.PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void eventsBelowTheAttemptLimitAreNotRetired() {
        AgentOutboxEvent event = save("still-trying", "{}");
        outbox.claim(event.getId(), NOW, "t0", NOW.plus(LEASE));
        outbox.markRetry(event.getId(), "t0", NOW, NOW, "transient");

        assertThat(maintenance.failExhausted()).isZero();
        assertThat(outbox.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(AgentOutboxStatus.PENDING);
    }

    // ------------------------------------------------------------------ broker confirms

    @Test
    void unroutableMessageIsNeverMarkedSent() {
        AgentOutboxEvent event = save("unroutable", "{\"value\":3}");
        AgentOutboxPublisher confirming = confirmingPublisher();
        answerWith(correlation -> {
            correlation.setReturned(new org.springframework.amqp.core.ReturnedMessage(
                    new org.springframework.amqp.core.Message(new byte[0]), 312, "NO_ROUTE",
                    RabbitMqConfig.AGENT_EXCHANGE, RabbitMqConfig.AGENT_STEP_ROUTING_KEY));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        confirming.publishAvailable(10);

        AgentOutboxEvent stored = outbox.findById(event.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AgentOutboxStatus.PENDING);
        assertThat(stored.getLastError()).contains("unroutable");
        assertThat(stored.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void nackedMessageGoesBackToPending() {
        AgentOutboxEvent event = save("nacked", "{\"value\":4}");
        AgentOutboxPublisher confirming = confirmingPublisher();
        answerWith(correlation ->
                correlation.getFuture().complete(new CorrelationData.Confirm(false, "queue full")));

        confirming.publishAvailable(10);

        AgentOutboxEvent stored = outbox.findById(event.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AgentOutboxStatus.PENDING);
        assertThat(stored.getLastError()).contains("nacked");
        assertThat(stored.getLastError()).contains("queue full");
    }

    @Test
    void confirmTimeoutIsTreatedAsUndeliveredRatherThanSent() {
        AgentOutboxEvent event = save("confirm-timeout", "{\"value\":5}");
        AgentOutboxPublisher impatient = new AgentOutboxPublisher(
                outbox, rabbit, Clock.fixed(NOW, ZoneOffset.UTC), RETRY_DELAY, 1_000, LEASE, Duration.ofMillis(50));
        stubPublisherConfirmsEnabled();
        // Deliberately never complete the future.
        answerWith(correlation -> { });

        impatient.publishAvailable(10);

        AgentOutboxEvent stored = outbox.findById(event.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AgentOutboxStatus.PENDING);
        assertThat(stored.getLastError()).contains("timed out");
    }

    @Test
    void acknowledgedMessageIsMarkedSent() {
        AgentOutboxEvent event = save("acked", "{\"value\":6}");
        AgentOutboxPublisher confirming = confirmingPublisher();
        answerWith(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

        assertThat(confirming.publishAvailable(10)).isEqualTo(1);
        assertThat(outbox.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(AgentOutboxStatus.SENT);
    }

    // ------------------------------------------------------------------ helpers

    private AgentOutboxEvent save(String key, String payload) {
        return outbox.save(AgentOutboxEvent.pending(key, 1L, "AGENT_STEP", payload, "trace-" + key, NOW));
    }

    private AgentOutboxPublisher confirmingPublisher() {
        stubPublisherConfirmsEnabled();
        return new AgentOutboxPublisher(
                outbox, rabbit, Clock.fixed(NOW, ZoneOffset.UTC), RETRY_DELAY, 1_000, LEASE, Duration.ofSeconds(2));
    }

    private void stubPublisherConfirmsEnabled() {
        org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory =
                org.mockito.Mockito.mock(org.springframework.amqp.rabbit.connection.ConnectionFactory.class);
        org.mockito.Mockito.when(connectionFactory.isPublisherConfirms()).thenReturn(true);
        org.mockito.Mockito.when(rabbit.getConnectionFactory()).thenReturn(connectionFactory);
    }

    /** Lets a test act on the {@link CorrelationData} the publisher hands to the broker. */
    private void answerWith(java.util.function.Consumer<CorrelationData> action) {
        doAnswer(invocation -> {
            action.accept(invocation.getArgument(3, CorrelationData.class));
            return null;
        }).when(rabbit).convertAndSend(
                eq(RabbitMqConfig.AGENT_EXCHANGE), eq(RabbitMqConfig.AGENT_STEP_ROUTING_KEY),
                org.mockito.ArgumentMatchers.anyString(), any(CorrelationData.class));
    }

    @SuppressWarnings("unused")
    private String uniqueKey() {
        return UUID.randomUUID().toString();
    }
}
