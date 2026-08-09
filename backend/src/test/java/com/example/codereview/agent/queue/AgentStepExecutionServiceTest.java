package com.example.codereview.agent.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.orchestration.AgentStepResult;
import com.example.codereview.agent.outbox.AgentOutboxRepository;
import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.run.AgentStep;
import com.example.codereview.agent.run.AgentStepRepository;
import com.example.codereview.agent.run.AgentStepStatus;
import com.example.codereview.ai.AiCallTransientException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Regression coverage for P0-04.
 *
 * <p>Step execution used to sit inside a single {@code @Transactional} method that also took a
 * pessimistic row lock, so every Git clone, model call and sandbox run held a database connection
 * and a lock for its whole duration.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false",
        "app.agent.recovery.enabled=false",
        "app.agent.step.lease-duration=3m",
        // Long enough that no background renewal interferes with these assertions.
        "app.agent.step.heartbeat-interval=300s"
})
@ActiveProfiles("dev")
class AgentStepExecutionServiceTest {

    @Autowired
    private AgentRunRepository runs;

    @Autowired
    private AgentStepRepository steps;

    @Autowired
    private AgentOutboxRepository outbox;

    @Autowired
    private AgentStepExecutionService execution;

    @Autowired
    private AgentStepClaimService claimService;

    @MockitoBean
    private AgentStepHandler handler;

    @BeforeEach
    void setUp() {
        outbox.deleteAll();
        steps.deleteAll();
        runs.deleteAll();
    }

    // ------------------------------------------------------------------ the actual P0-04 fix

    @Test
    void handlerRunsWithNoTransactionOpen() {
        Fixture fixture = fixture(AgentRunStatus.PREPARING_REPOSITORY);
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        AtomicBoolean synchronizationActive = new AtomicBoolean(true);
        when(handler.execute(any())).thenAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            synchronizationActive.set(TransactionSynchronizationManager.isSynchronizationActive());
            return checkpoint(invocation.getArgument(0));
        });

        execution.execute(fixture.message());

        assertThat(transactionActive)
                .as("a step handler must not run inside a business transaction")
                .isFalse();
        assertThat(synchronizationActive)
                .as("no transaction synchronization should be bound while the handler runs")
                .isFalse();
    }

    @Test
    void leaseIsHeldWhileTheHandlerRunsAndReleasedAfterwards() {
        Fixture fixture = fixture(AgentRunStatus.PREPARING_REPOSITORY);
        AtomicReference<AgentStep> during = new AtomicReference<>();
        when(handler.execute(any())).thenAnswer(invocation -> {
            during.set(steps.findById(fixture.stepId()).orElseThrow());
            return checkpoint(invocation.getArgument(0));
        });

        execution.execute(fixture.message());

        assertThat(during.get().getStatus()).isEqualTo(AgentStepStatus.RUNNING);
        assertThat(during.get().getExecutionToken()).isNotBlank();
        assertThat(during.get().getLeaseExpiresAt()).isNotNull();
        assertThat(during.get().getWorkerId()).isNotBlank();

        AgentStep after = steps.findById(fixture.stepId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AgentStepStatus.SUCCEEDED);
        assertThat(after.getExecutionToken()).isNull();
        assertThat(after.getLeaseExpiresAt()).isNull();
    }

    // ------------------------------------------------------------------ claim exclusivity

    @Test
    void onlyOneMessageCanClaimTheSameStep() {
        Fixture fixture = fixture(AgentRunStatus.PREPARING_REPOSITORY);

        AgentStepClaim first = claimService.claim(fixture.message());
        AgentStepClaim second = claimService.claim(fixture.message());

        assertThat(first.isClaimed()).isTrue();
        assertThat(second.isClaimed()).isFalse();
        assertThat(second.rejection())
                .isEqualTo(AgentStepExecutionService.ExecutionOutcome.STALE_OR_ACTIVE_IGNORED);
    }

    @Test
    void alreadySucceededStepIsIgnoredAsADuplicate() {
        Fixture fixture = fixture(AgentRunStatus.PREPARING_REPOSITORY);
        when(handler.execute(any())).thenAnswer(invocation -> checkpoint(invocation.getArgument(0)));
        execution.execute(fixture.message());

        assertThat(execution.execute(fixture.message()))
                .isEqualTo(AgentStepExecutionService.ExecutionOutcome.DUPLICATE_IGNORED);
    }

    // ------------------------------------------------------------------ stale worker results

    @Test
    void resultFromAWorkerThatLostItsLeaseIsDiscarded() {
        Fixture fixture = fixture(AgentRunStatus.PREPARING_REPOSITORY);
        // While the handler runs, simulate the watchdog reclaiming the step and handing it to a new
        // worker: the token changes underneath the one that is still executing.
        when(handler.execute(any())).thenAnswer(invocation -> {
            AgentStep reclaimed = steps.findById(fixture.stepId()).orElseThrow();
            reclaimed.leaseTo("someone-elses-token", "other-worker", Instant.now().plusSeconds(120));
            steps.saveAndFlush(reclaimed);
            return checkpoint(invocation.getArgument(0));
        });

        AgentStepExecutionService.ExecutionOutcome outcome = execution.execute(fixture.message());

        assertThat(outcome).isEqualTo(AgentStepExecutionService.ExecutionOutcome.LEASE_LOST);
        AgentStep step = steps.findById(fixture.stepId()).orElseThrow();
        assertThat(step.getStatus())
                .as("the late worker must not be able to mark the step succeeded")
                .isEqualTo(AgentStepStatus.RUNNING);
        assertThat(step.getExecutionToken()).isEqualTo("someone-elses-token");
        assertThat(outbox.count()).as("a discarded result must not enqueue the next step").isZero();
    }

    @Test
    void failureFromAWorkerThatLostItsLeaseIsAlsoDiscarded() {
        Fixture fixture = fixture(AgentRunStatus.PREPARING_REPOSITORY);
        when(handler.execute(any())).thenAnswer(invocation -> {
            AgentStep reclaimed = steps.findById(fixture.stepId()).orElseThrow();
            reclaimed.leaseTo("someone-elses-token", "other-worker", Instant.now().plusSeconds(120));
            steps.saveAndFlush(reclaimed);
            throw new IllegalStateException("boom");
        });

        assertThat(execution.execute(fixture.message()))
                .isEqualTo(AgentStepExecutionService.ExecutionOutcome.LEASE_LOST);
        assertThat(steps.findById(fixture.stepId()).orElseThrow().getStatus())
                .isEqualTo(AgentStepStatus.RUNNING);
    }

    // ------------------------------------------------------------------ transient provider failures
    //
    // 首次真实运行即暴露(run14,2026-08-08):handler 抛出的 AiCallTransientException 曾落进兜底的
    // catch (RuntimeException),被按 INTERNAL_ERROR 直接终态,重试机制全程闲置。以下两条锁死
    // "瞬态 provider 错误 → 调度重试;耗尽 attempt → 才允许终态 FAILED" 的映射。

    @Test
    void transientProviderFailureSchedulesARetryInsteadOfFailingTheRun() {
        Fixture fixture = fixture(AgentRunStatus.PREPARING_REPOSITORY);
        when(handler.execute(any()))
                .thenThrow(new AiCallTransientException(
                        "LangChain4j provider call failed transiently (InternalServerException)", null));

        AgentStepExecutionService.ExecutionOutcome outcome = execution.execute(fixture.message());

        assertThat(outcome).isEqualTo(AgentStepExecutionService.ExecutionOutcome.RETRY_SCHEDULED);
        AgentRun run = runs.findById(fixture.runId()).orElseThrow();
        assertThat(run.getStatus())
                .as("a transient provider hiccup must park the run for retry, not kill it")
                .isEqualTo(AgentRunStatus.RETRY_WAIT);
        assertThat(steps.findById(fixture.stepId()).orElseThrow().getStatus())
                .isEqualTo(AgentStepStatus.FAILED);
        assertThat(outbox.count()).as("the retry message must be enqueued through the outbox").isEqualTo(1);
    }

    @Test
    void transientProviderFailureFailsTheRunOnceAttemptsAreExhausted() {
        Fixture fixture = fixture(AgentRunStatus.PREPARING_REPOSITORY);
        when(handler.execute(any()))
                .thenThrow(new AiCallTransientException(
                        "LangChain4j provider call failed transiently (InternalServerException)", null));

        // app.agent.queue.max-retry defaults to 3. Walk the real retry chain: a FAILED step only
        // accepts attempt n+1, so attempts 0..2 each park the run in RETRY_WAIT again.
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(execution.execute(new AgentStepMessage(fixture.runId(), 1, attempt, "trace-a2")))
                    .as("attempt %d is below the cap and must be retried", attempt)
                    .isEqualTo(AgentStepExecutionService.ExecutionOutcome.RETRY_SCHEDULED);
        }

        AgentStepExecutionService.ExecutionOutcome outcome =
                execution.execute(new AgentStepMessage(fixture.runId(), 1, 3, "trace-a2"));

        assertThat(outcome).isEqualTo(AgentStepExecutionService.ExecutionOutcome.FAILED);
        assertThat(runs.findById(fixture.runId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.FAILED);
        assertThat(outbox.count())
                .as("exactly the three retryable attempts may enqueue a retry, never a fourth")
                .isEqualTo(3);
    }

    // ------------------------------------------------------------------ atomicity and heartbeat

    @Test
    void advancingStepEnqueuesTheNextOneAtomicallyWithItsCompletion() {
        Fixture fixture = fixture(AgentRunStatus.PREPARING_REPOSITORY);
        when(handler.execute(any())).thenAnswer(invocation -> {
            AgentStepExecutionContext context = invocation.getArgument(0);
            return new AgentStepResult(
                    "agent-step-result-v1",
                    context.stepType(),
                    AgentStepResult.Disposition.ADVANCE,
                    AgentRunStatus.ANALYZING_CHANGE,
                    Map.of());
        });

        assertThat(execution.execute(fixture.message()))
                .isEqualTo(AgentStepExecutionService.ExecutionOutcome.SUCCEEDED);

        assertThat(steps.findById(fixture.stepId()).orElseThrow().getStatus())
                .isEqualTo(AgentStepStatus.SUCCEEDED);
        assertThat(outbox.count())
                .as("completing a step and scheduling the next must commit together")
                .isEqualTo(1);
    }

    @Test
    void heartbeatExtendsTheLeaseOnlyForTheRightfulHolder() {
        Fixture fixture = fixture(AgentRunStatus.PREPARING_REPOSITORY);
        AgentStepClaim claim = claimService.claim(fixture.message());
        Instant extended = Instant.now().plusSeconds(600);

        assertThat(steps.renewLease(fixture.stepId(), claim.executionToken(), extended, Instant.now()))
                .isEqualTo(1);
        assertThat(steps.renewLease(fixture.stepId(), "not-my-token", extended, Instant.now()))
                .as("a worker that lost the step must not be able to extend its lease")
                .isZero();
    }

    // ------------------------------------------------------------------ helpers

    private AgentStepResult checkpoint(AgentStepExecutionContext context) {
        return AgentStepResult.checkpoint(context.stepType());
    }

    private Fixture fixture(AgentRunStatus stepType) {
        AgentRun run = runs.save(new AgentRun(1L, 2L, 3L, "trigger-" + stepType, "abc123"));
        run.advanceTo(stepType, 1);
        runs.saveAndFlush(run);
        AgentStep step = steps.saveAndFlush(AgentStep.pending(run.getId(), 1, stepType));
        return new Fixture(run.getId(), step.getId(), new AgentStepMessage(run.getId(), 1, 0, "trace-a2"));
    }

    private record Fixture(Long runId, Long stepId, AgentStepMessage message) {
    }
}
