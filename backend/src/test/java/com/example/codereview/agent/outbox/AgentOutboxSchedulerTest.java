package com.example.codereview.agent.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The regression test for P0-01. Nothing in this class calls {@code publishAvailable}: the whole
 * point is that production has something that does it on its own.
 *
 * <p>Before the scheduler existed, an Agent run enqueued its first event and stopped there, because
 * the only caller of the publisher was test code.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false",
        "app.agent.outbox.scheduler.enabled=true",
        "app.agent.outbox.initial-delay-ms=100",
        "app.agent.outbox.fixed-delay-ms=100",
        // Both requeue and retry push next-attempt-at out by this much; the default 10s would make
        // these tests race their own timeouts.
        "app.agent.outbox.retry-delay=100ms",
        "app.agent.outbox.max-attempts=2"
})
@ActiveProfiles("dev")
class AgentOutboxSchedulerTest {

    @Autowired
    private AgentOutboxRepository outbox;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private RabbitTemplate rabbit;

    @BeforeEach
    void setUp() {
        outbox.deleteAll();
    }

    @Test
    void schedulerIsRegisteredWhenEnabled() {
        assertThat(context.getBeansOfType(AgentOutboxScheduler.class)).isNotEmpty();
    }

    @Test
    void pendingEventIsPublishedWithoutAnyManualTrigger() {
        AgentOutboxEvent event = outbox.save(AgentOutboxEvent.pending(
                "auto-publish", 1L, "AGENT_STEP", "{\"agentRunId\":1}", "trace-auto", Instant.now()));

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(outbox.findById(event.getId()).orElseThrow().getStatus())
                        .isEqualTo(AgentOutboxStatus.SENT));
    }

    @Test
    void stuckProcessingEventIsReclaimedAndDrainedByTheSchedulerAlone() {
        AgentOutboxEvent event = outbox.save(AgentOutboxEvent.pending(
                "stuck", 1L, "AGENT_STEP", "{\"agentRunId\":2}", "trace-stuck", Instant.now()));
        // Simulate a worker that claimed the event and then died: PROCESSING, lease already expired.
        outbox.claim(event.getId(), Instant.now(), "dead-worker", Instant.now().minusSeconds(120));

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(outbox.findById(event.getId()).orElseThrow().getStatus())
                        .isEqualTo(AgentOutboxStatus.SENT));
    }

    @Test
    void permanentlyUnroutableEventEndsUpFailedInsteadOfLoopingForever() {
        AgentOutboxEvent event = outbox.save(AgentOutboxEvent.pending(
                "never-routable", 1L, "NOT_A_REAL_EVENT_TYPE", "{}", "trace-dead", Instant.now()));

        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(outbox.findById(event.getId()).orElseThrow().getStatus())
                        .isEqualTo(AgentOutboxStatus.FAILED));

        assertThat(outbox.findById(event.getId()).orElseThrow().getFailedAt()).isNotNull();
    }
}
