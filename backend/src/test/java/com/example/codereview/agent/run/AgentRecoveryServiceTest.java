package com.example.codereview.agent.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.agent.outbox.AgentOutboxEvent;
import com.example.codereview.agent.outbox.AgentOutboxRepository;
import com.example.codereview.agent.queue.AgentStepMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false",
        "app.agent.recovery.enabled=false",
        "app.agent.recovery.stale-threshold=5m",
        "app.agent.recovery.batch-size=20"
})
@ActiveProfiles("dev")
class AgentRecoveryServiceTest {

    @Autowired
    private AgentRunRepository runs;

    @Autowired
    private AgentStepRepository steps;

    @Autowired
    private AgentOutboxRepository outbox;

    @Autowired
    private AgentRecoveryService recovery;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        outbox.deleteAll();
        steps.deleteAll();
        runs.deleteAll();
    }

    @Test
    void staleRunningStepIsInterruptedAndRepublishedOnce() throws Exception {
        Fixture fixture = runningFixture(
                "recovery-stale",
                AgentRunStatus.PREPARING_REPOSITORY,
                true,
                true
        );

        assertThat(recovery.recoverStaleRuns()).isEqualTo(1);
        assertThat(recovery.recoverStaleRuns()).isZero();

        AgentStep recovered = steps.findById(fixture.stepId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(AgentStepStatus.INTERRUPTED);
        assertThat(recovered.getAttempt()).isZero();
        assertThat(recovered.getErrorMessage()).contains("restart interrupted");

        assertThat(outbox.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo("AGENT_STEP");
            AgentStepMessage message = read(event);
            assertThat(message.agentRunId()).isEqualTo(fixture.runId());
            assertThat(message.sequenceNo()).isEqualTo(1);
            assertThat(message.attempt()).isEqualTo(1);
            assertThat(event.getEventKey()).isEqualTo(message.identity());
        });
    }

    @Test
    void waitingApprovalAndTerminalRunsAreNotRepublished() {
        runningFixture("recovery-approval", AgentRunStatus.WAITING_APPROVAL, true, true);
        runningFixture("recovery-terminal", AgentRunStatus.FAILED, true, true);

        assertThat(recovery.recoverStaleRuns()).isZero();
        assertThat(outbox.count()).isZero();
        assertThat(steps.findAll())
                .allMatch(step -> step.getStatus() == AgentStepStatus.RUNNING);
    }

    @Test
    void recentlyUpdatedActiveRunIsNotRepublished() {
        Fixture fixture = runningFixture(
                "recovery-recent-run",
                AgentRunStatus.PREPARING_REPOSITORY,
                true,
                false
        );

        assertThat(recovery.recoverStaleRuns()).isZero();
        assertThat(steps.findById(fixture.stepId()).orElseThrow().getStatus())
                .isEqualTo(AgentStepStatus.RUNNING);
        assertThat(outbox.count()).isZero();
    }

    @Test
    void recentlyUpdatedStepIsNotRepublished() {
        Fixture fixture = runningFixture(
                "recovery-recent-step",
                AgentRunStatus.PREPARING_REPOSITORY,
                false,
                true
        );

        assertThat(recovery.recoverStaleRuns()).isZero();
        assertThat(steps.findById(fixture.stepId()).orElseThrow().getStatus())
                .isEqualTo(AgentStepStatus.RUNNING);
        assertThat(outbox.count()).isZero();
    }

    @Test
    void atomicInterruptClaimCanSucceedOnlyOnce() {
        Fixture fixture = runningFixture(
                "recovery-claim",
                AgentRunStatus.PREPARING_REPOSITORY,
                true,
                true
        );
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofMinutes(5));
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        Integer first = transactions.execute(status -> claim(fixture.stepId(), cutoff, now));
        Integer second = transactions.execute(status -> claim(fixture.stepId(), cutoff, now));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(steps.findById(fixture.stepId()).orElseThrow().getStatus())
                .isEqualTo(AgentStepStatus.INTERRUPTED);
    }

    private int claim(Long stepId, Instant cutoff, Instant now) {
        return steps.interruptIfStale(
                stepId,
                AgentStepStatus.RUNNING,
                AgentStepStatus.INTERRUPTED,
                cutoff,
                now,
                "claimed for recovery",
                Set.of(AgentRunStatus.PREPARING_REPOSITORY)
        );
    }

    private Fixture runningFixture(
            String triggerKey,
            AgentRunStatus runStatus,
            boolean staleStep,
            boolean staleRun
    ) {
        AgentRun run = runs.save(new AgentRun(1L, 2L, 3L, triggerKey, "abc"));
        run.advanceTo(runStatus, 1);
        run = runs.saveAndFlush(run);

        AgentStep step = AgentStep.pending(run.getId(), 1, AgentRunStatus.PREPARING_REPOSITORY);
        step.start(0);
        step = steps.saveAndFlush(step);

        OffsetDateTime stale = OffsetDateTime.ofInstant(
                Instant.now().minus(Duration.ofMinutes(10)),
                ZoneOffset.UTC
        );
        if (staleStep) {
            jdbc.update(
                    "update agent_step set started_at = ?, updated_at = ? where id = ?",
                    stale,
                    stale,
                    step.getId()
            );
        }
        if (staleRun) {
            jdbc.update("update agent_run set updated_at = ? where id = ?", stale, run.getId());
        }
        return new Fixture(run.getId(), step.getId());
    }

    private AgentStepMessage read(AgentOutboxEvent event) {
        try {
            return objectMapper.readValue(event.getPayload(), AgentStepMessage.class);
        } catch (Exception exception) {
            throw new AssertionError("Unable to read recovery outbox payload", exception);
        }
    }

    private record Fixture(Long runId, Long stepId) {
    }
}
