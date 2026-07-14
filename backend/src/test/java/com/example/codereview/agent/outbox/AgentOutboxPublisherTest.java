package com.example.codereview.agent.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false"
})
@ActiveProfiles("dev")
class AgentOutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-06-19T00:00:00Z");

    @Autowired
    private AgentRunRepository runs;

    @Autowired
    private AgentOutboxRepository outbox;

    @Autowired
    private AgentRunTransitionService transitions;

    @MockitoBean
    private RabbitTemplate rabbit;

    private AgentOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        outbox.deleteAll();
        runs.deleteAll();
        reset(rabbit);
        publisher = new AgentOutboxPublisher(
                outbox,
                rabbit,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(10),
                1_000
        );
    }

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
        outbox.saveAndFlush(AgentOutboxEvent.pending(
                eventKey, run.getId(), "AGENT_STEP", "{}", "trace-1", NOW
        ));

        assertThatThrownBy(() -> transitions.transitionAndEnqueue(
                run.getId(),
                AgentRunStatus.PREPARING_REPOSITORY,
                1,
                eventKey,
                "{}",
                "trace-1"
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(runs.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.RECEIVED);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void mqFailureLeavesPendingEventWithRetryMetadata() {
        AgentOutboxEvent event = outbox.save(AgentOutboxEvent.pending(
                "failure-key", 1L, "AGENT_STEP", "{\"value\":1}", "trace-2", NOW
        ));
        doThrow(new IllegalStateException("broker unavailable"))
                .when(rabbit)
                .convertAndSend(
                        eq(RabbitMqConfig.AGENT_EXCHANGE),
                        eq(RabbitMqConfig.AGENT_STEP_ROUTING_KEY),
                        eq("{\"value\":1}")
                );

        publisher.publishAvailable(10);

        AgentOutboxEvent failed = outbox.findById(event.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(AgentOutboxStatus.PENDING);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(failed.getLastError()).contains("broker unavailable");
    }

    @Test
    void atomicClaimAllowsOnlyOnePublisher() {
        AgentOutboxEvent event = outbox.save(AgentOutboxEvent.pending(
                "claim-once", 1L, "AGENT_STEP", "{}", "trace-claim", NOW
        ));

        assertThat(outbox.claim(event.getId(), NOW)).isEqualTo(1);
        assertThat(outbox.claim(event.getId(), NOW)).isZero();
        assertThat(outbox.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentOutboxStatus.PROCESSING);
    }

    @Test
    void sentEventIsNotPublishedAgain() {
        outbox.save(AgentOutboxEvent.pending(
                "sent-once", 1L, "AGENT_STEP", "{\"value\":2}", "trace-3", NOW
        ));

        publisher.publishAvailable(10);
        publisher.publishAvailable(10);

        verify(rabbit, times(1)).convertAndSend(
                RabbitMqConfig.AGENT_EXCHANGE,
                RabbitMqConfig.AGENT_STEP_ROUTING_KEY,
                "{\"value\":2}"
        );
        assertThat(outbox.findAll()).singleElement()
                .extracting(AgentOutboxEvent::getStatus)
                .isEqualTo(AgentOutboxStatus.SENT);
    }
}
