package com.example.codereview.agent.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.outbox.AgentOutboxEvent;
import com.example.codereview.agent.orchestration.AgentStepResult;
import com.example.codereview.agent.outbox.AgentOutboxRepository;
import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.run.AgentStep;
import com.example.codereview.agent.run.AgentStepRepository;
import com.example.codereview.agent.run.AgentStepStatus;
import com.example.codereview.config.RabbitMqConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false",
        "app.agent.queue.max-retry=3",
        "app.agent.queue.retry-delay-ms=5000"
})
@ActiveProfiles("dev")
class AgentStepConsumerTest {

    @Autowired
    private AgentRunRepository runs;

    @Autowired
    private AgentStepRepository steps;

    @Autowired
    private AgentOutboxRepository outbox;

    @Autowired
    private AgentStepConsumer consumer;

    @Autowired
    private AgentStepPublisher publisher;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private AgentStepHandler handler;

    @BeforeEach
    void setUp() {
        outbox.deleteAll();
        steps.deleteAll();
        runs.deleteAll();
        reset(handler);
    }

    @Test
    void initialScheduleIsIdempotentAndWritesOutbox() throws Exception {
        AgentRun run = runs.save(new AgentRun(1L, 2L, 3L, "task8-schedule", "abc"));

        AgentStepMessage scheduled = publisher.schedule(
                run.getId(),
                1,
                AgentRunStatus.PREPARING_REPOSITORY,
                "trace-schedule"
        );
        assertThat(publisher.schedule(
                run.getId(),
                1,
                AgentRunStatus.PREPARING_REPOSITORY,
                "trace-schedule"
        )).isEqualTo(scheduled);

        assertThat(runs.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.PREPARING_REPOSITORY);
        assertThat(steps.findByAgentRunIdOrderBySequenceNo(run.getId())).singleElement()
                .extracting(AgentStep::getStatus)
                .isEqualTo(AgentStepStatus.PENDING);
        assertThat(outbox.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo("AGENT_STEP");
            assertThat(event.getEventKey()).isEqualTo(scheduled.identity());
            assertThat(objectMapper.readValue(event.getPayload(), AgentStepMessage.class))
                    .isEqualTo(scheduled);
        });
    }
    @Test
    void successfulStepIgnoresDuplicateDelivery() {
        Fixture fixture = fixture("task8-success");
        AgentStepMessage message = fixture.message(0);
        when(handler.execute(any())).thenReturn(
                AgentStepResult.checkpoint(AgentRunStatus.PREPARING_REPOSITORY)
        );

        assertThat(consumer.consume(message))
                .isEqualTo(AgentStepExecutionService.ExecutionOutcome.SUCCEEDED);
        assertThat(consumer.consume(message))
                .isEqualTo(AgentStepExecutionService.ExecutionOutcome.DUPLICATE_IGNORED);

        verify(handler, times(1)).execute(any());
        AgentStep persisted = step(fixture.run().getId());
        assertThat(persisted.getStatus()).isEqualTo(AgentStepStatus.SUCCEEDED);
        assertThat(persisted.getAttempt()).isZero();
        assertThat(persisted.getOutputSummary())
                .contains("agent-step-result-v1", "PREPARING_REPOSITORY", "typed-executor-ready");
    }

    @Test
    void retryableInfrastructureFailureSchedulesOnlyNextAttempt() throws Exception {
        Fixture fixture = fixture("task8-retry");
        AgentStepMessage message = fixture.message(0);
        when(handler.execute(any())).thenThrow(new AgentStepExecutionException(
                AgentFailureType.RETRYABLE_INFRASTRUCTURE,
                "temporary broker failure"
        ));

        assertThat(consumer.consume(message))
                .isEqualTo(AgentStepExecutionService.ExecutionOutcome.RETRY_SCHEDULED);

        assertThat(runs.findById(fixture.run().getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.RETRY_WAIT);
        assertThat(step(fixture.run().getId()).getStatus()).isEqualTo(AgentStepStatus.FAILED);

        List<AgentOutboxEvent> events = outbox.findAll();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo("AGENT_STEP_DELAY");
            assertThat(event.getEventKey()).isEqualTo(fixture.message(1).identity());
            assertThat(objectMapper.readValue(event.getPayload(), AgentStepMessage.class))
                    .isEqualTo(fixture.message(1));
        });

        assertThat(consumer.consume(message))
                .isEqualTo(AgentStepExecutionService.ExecutionOutcome.STALE_OR_ACTIVE_IGNORED);
        assertThat(outbox.count()).isEqualTo(1);
        verify(handler, times(1)).execute(any());
    }

    @Test
    void nextAttemptResumesRetryWaitAndCanSucceed() {
        Fixture fixture = fixture("task8-retry-success");
        AgentStepMessage first = fixture.message(0);
        AgentStepMessage retry = fixture.message(1);
        when(handler.execute(any())).thenAnswer(invocation -> {
            var context = invocation.getArgument(0, com.example.codereview.agent.orchestration.AgentStepExecutionContext.class);
            if (context.attempt() == 0) {
                throw new AgentStepExecutionException(
                        AgentFailureType.RETRYABLE_INFRASTRUCTURE,
                        "temporary"
                );
            }
            return AgentStepResult.checkpoint(AgentRunStatus.PREPARING_REPOSITORY);
        });

        assertThat(consumer.consume(first))
                .isEqualTo(AgentStepExecutionService.ExecutionOutcome.RETRY_SCHEDULED);
        assertThat(consumer.consume(retry))
                .isEqualTo(AgentStepExecutionService.ExecutionOutcome.SUCCEEDED);

        AgentRun persistedRun = runs.findById(fixture.run().getId()).orElseThrow();
        AgentStep persistedStep = step(fixture.run().getId());
        assertThat(persistedRun.getStatus()).isEqualTo(AgentRunStatus.PREPARING_REPOSITORY);
        assertThat(persistedStep.getStatus()).isEqualTo(AgentStepStatus.SUCCEEDED);
        assertThat(persistedStep.getAttempt()).isEqualTo(1);
    }

    @Test
    void securityFailureFailsRunWithoutRetry() {
        assertNonRetryableFailure(
                "task8-security",
                AgentFailureType.SECURITY_VIOLATION,
                "unsafe path"
        );
    }

    @Test
    void budgetFailureFailsRunWithoutRetry() {
        assertNonRetryableFailure(
                "task8-budget",
                AgentFailureType.BUDGET_EXCEEDED,
                "tool budget exhausted"
        );
    }

    @Test
    void retryableProviderFailureSchedulesRetry() {
        Fixture fixture = fixture("task8-provider-retry");
        when(handler.execute(any())).thenThrow(new AgentStepExecutionException(
                AgentFailureType.RETRYABLE_PROVIDER_ERROR,
                "provider unavailable"
        ));

        assertThat(consumer.consume(fixture.message(0)))
                .isEqualTo(AgentStepExecutionService.ExecutionOutcome.RETRY_SCHEDULED);
        assertThat(runs.findById(fixture.run().getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.RETRY_WAIT);
    }

    @Test
    void typedPermanentFailuresRemainDistinctAndDoNotRetry() {
        assertNonRetryableFailure(
                "task8-provider-permanent",
                AgentFailureType.PERMANENT_PROVIDER_ERROR,
                "provider rejected request"
        );
        setUp();
        assertNonRetryableFailure(
                "task8-environment-incomplete",
                AgentFailureType.ENVIRONMENT_INCOMPLETE,
                "dependency cache missing"
        );
        setUp();
        assertNonRetryableFailure(
                "task8-invalid-model",
                AgentFailureType.INVALID_MODEL_OUTPUT,
                "fabricated citation"
        );
    }

    @Test
    void cancellationIsCheckedBeforeExecutorDispatch() {
        Fixture fixture = fixture("task8-canceled-before-dispatch");
        AgentRun run = runs.findById(fixture.run().getId()).orElseThrow();
        run.requestCancellation();
        runs.saveAndFlush(run);

        assertThat(consumer.consume(fixture.message(0)))
                .isEqualTo(AgentStepExecutionService.ExecutionOutcome.FAILED);

        assertThat(runs.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.CANCELED);
        verify(handler, never()).execute(any());
    }

    @Test
    void agentQueueTopologyUsesDedicatedRoutingAndDelayDeadLettering() {
        Queue stepQueue = context.getBean("agentStepQueue", Queue.class);
        Queue delayQueue = context.getBean("agentDelayQueue", Queue.class);
        Queue cancelQueue = context.getBean("agentCancelQueue", Queue.class);
        Queue deadQueue = context.getBean("agentDeadQueue", Queue.class);

        assertThat(stepQueue.getName()).isEqualTo(RabbitMqConfig.AGENT_STEP_QUEUE);
        assertThat(stepQueue.getArguments())
                .containsEntry("x-dead-letter-exchange", RabbitMqConfig.AGENT_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", RabbitMqConfig.AGENT_DEAD_ROUTING_KEY);
        assertThat(delayQueue.getName()).isEqualTo(RabbitMqConfig.AGENT_DELAY_QUEUE);
        assertThat(delayQueue.getArguments())
                .containsEntry("x-dead-letter-exchange", RabbitMqConfig.AGENT_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", RabbitMqConfig.AGENT_STEP_ROUTING_KEY)
                .containsEntry("x-message-ttl", 5000);
        assertThat(cancelQueue.getName()).isEqualTo(RabbitMqConfig.AGENT_CANCEL_QUEUE);
        assertThat(deadQueue.getName()).isEqualTo(RabbitMqConfig.AGENT_DEAD_QUEUE);
    }

    private void assertNonRetryableFailure(
            String triggerKey,
            AgentFailureType failureType,
            String messageText
    ) {
        Fixture fixture = fixture(triggerKey);
        AgentStepMessage message = fixture.message(0);
        when(handler.execute(any())).thenThrow(new AgentStepExecutionException(
                failureType,
                messageText
        ));

        assertThat(consumer.consume(message))
                .isEqualTo(AgentStepExecutionService.ExecutionOutcome.FAILED);

        assertThat(runs.findById(fixture.run().getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.FAILED);
        AgentStep persisted = step(fixture.run().getId());
        assertThat(persisted.getStatus()).isEqualTo(AgentStepStatus.FAILED);
        assertThat(persisted.getErrorMessage()).contains(messageText);
        assertThat(outbox.count()).isZero();
    }

    private Fixture fixture(String triggerKey) {
        AgentRun run = runs.save(new AgentRun(1L, 2L, 3L, triggerKey, "abc"));
        run.advanceTo(AgentRunStatus.PREPARING_REPOSITORY, 1);
        run = runs.saveAndFlush(run);
        steps.saveAndFlush(AgentStep.pending(
                run.getId(),
                1,
                AgentRunStatus.PREPARING_REPOSITORY
        ));
        return new Fixture(run, "trace-" + triggerKey);
    }

    private AgentStep step(Long runId) {
        return steps.findByAgentRunIdAndSequenceNo(runId, 1).orElseThrow();
    }

    private record Fixture(AgentRun run, String traceId) {
        private AgentStepMessage message(int attempt) {
            return new AgentStepMessage(run.getId(), 1, attempt, traceId);
        }
    }
}
