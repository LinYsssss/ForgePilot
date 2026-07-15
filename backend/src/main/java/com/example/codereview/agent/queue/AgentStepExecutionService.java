package com.example.codereview.agent.queue;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.observability.AgentMetrics;
import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.run.AgentStateMachine;
import com.example.codereview.agent.run.AgentStep;
import com.example.codereview.agent.run.AgentStepRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentStepExecutionService {

    private final AgentRunRepository runs;
    private final AgentStepRepository steps;
    private final AgentStateMachine stateMachine;
    private final AgentStepHandler handler;
    private final AgentStepPublisher publisher;
    private final AgentMetrics metrics;
    private final int maxRetry;

    public AgentStepExecutionService(
            AgentRunRepository runs,
            AgentStepRepository steps,
            AgentStateMachine stateMachine,
            AgentStepHandler handler,
            AgentStepPublisher publisher,
            AgentMetrics metrics,
            @Value("${app.agent.queue.max-retry:3}") int maxRetry
    ) {
        this.runs = runs;
        this.steps = steps;
        this.stateMachine = stateMachine;
        this.handler = handler;
        this.publisher = publisher;
        this.metrics = metrics;
        this.maxRetry = maxRetry;
    }

    @Transactional
    public ExecutionOutcome execute(AgentStepMessage message) {
        AgentRun run = runs.findById(message.agentRunId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Agent run not found: " + message.agentRunId()
                ));
        AgentStep step = steps.findForUpdate(message.agentRunId(), message.sequenceNo())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Agent step not found: " + message.identity()
                ));

        long startedNanos = System.nanoTime();
        ExecutionOutcome outcome = runLoadedStep(run, step, message);
        metrics.recordStep(step.getStepType(), outcome.name(), (System.nanoTime() - startedNanos) / 1_000_000);
        return outcome;
    }

    private ExecutionOutcome runLoadedStep(AgentRun run, AgentStep step, AgentStepMessage message) {
        if (step.isSucceeded()) {
            return ExecutionOutcome.DUPLICATE_IGNORED;
        }
        if (!step.acceptsAttempt(message.attempt())) {
            return ExecutionOutcome.STALE_OR_ACTIVE_IGNORED;
        }

        activateRetryRun(run, step);
        step.start(message.attempt());
        steps.saveAndFlush(step);

        try {
            String output = handler.execute(message);
            step.succeed(output);
            steps.save(step);
            return ExecutionOutcome.SUCCEEDED;
        } catch (AgentStepExecutionException exception) {
            return handleFailure(run, step, message, exception.getFailureType(), exception.getMessage());
        } catch (RuntimeException exception) {
            return handleFailure(
                    run, step, message, AgentFailureType.INTERNAL_ERROR, failureMessage(exception)
            );
        }
    }

    private void activateRetryRun(AgentRun run, AgentStep step) {
        if (run.getStatus() != AgentRunStatus.RETRY_WAIT) {
            if (run.getStatus() != step.getStepType()) {
                throw new IllegalStateException(
                        "Agent run status " + run.getStatus() + " does not match step " + step.getStepType()
                );
            }
            return;
        }
        stateMachine.requireTransition(run.getStatus(), step.getStepType());
        run.advanceTo(step.getStepType(), step.getSequenceNo());
    }

    private ExecutionOutcome handleFailure(
            AgentRun run,
            AgentStep step,
            AgentStepMessage message,
            AgentFailureType failureType,
            String reason
    ) {
        step.fail(reason);
        steps.save(step);

        if (failureType == AgentFailureType.RETRYABLE_INFRASTRUCTURE
                && message.attempt() < maxRetry) {
            publisher.scheduleRetry(message);
            return ExecutionOutcome.RETRY_SCHEDULED;
        }

        AgentRunStatus terminal = failureType == AgentFailureType.CANCELED
                ? AgentRunStatus.CANCELED
                : AgentRunStatus.FAILED;
        if (run.getStatus() != terminal) {
            stateMachine.requireTransition(run.getStatus(), terminal);
            run.advanceTo(terminal, step.getSequenceNo());
        }
        return ExecutionOutcome.FAILED;
    }

    private String failureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getName() : message;
    }

    public enum ExecutionOutcome {
        SUCCEEDED,
        RETRY_SCHEDULED,
        FAILED,
        DUPLICATE_IGNORED,
        STALE_OR_ACTIVE_IGNORED
    }
}
