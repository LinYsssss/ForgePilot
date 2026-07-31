package com.example.codereview.agent.queue;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.orchestration.AgentStepResult;
import com.example.codereview.agent.queue.AgentStepExecutionService.ExecutionOutcome;
import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.run.AgentStateMachine;
import com.example.codereview.agent.run.AgentStep;
import com.example.codereview.agent.run.AgentStepRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase three of step execution: record the outcome, but only if this worker still holds the lease.
 *
 * <p>Enqueueing the follow-up step happens inside the same transaction as the completion, so a run
 * can never be left having succeeded a step without the next one being scheduled.
 */
@Service
public class AgentStepCompletionService {

    private static final Logger log = LoggerFactory.getLogger(AgentStepCompletionService.class);

    private final AgentRunRepository runs;
    private final AgentStepRepository steps;
    private final AgentStateMachine stateMachine;
    private final AgentStepPublisher publisher;
    private final ObjectMapper objectMapper;
    private final int maxRetry;

    public AgentStepCompletionService(
            AgentRunRepository runs,
            AgentStepRepository steps,
            AgentStateMachine stateMachine,
            AgentStepPublisher publisher,
            ObjectMapper objectMapper,
            @Value("${app.agent.queue.max-retry:3}") int maxRetry
    ) {
        this.runs = runs;
        this.steps = steps;
        this.stateMachine = stateMachine;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.maxRetry = maxRetry;
    }

    @Transactional
    public ExecutionOutcome complete(
            AgentStepExecutionContext context,
            String executionToken,
            AgentStepMessage message,
            AgentStepResult result
    ) {
        AgentStep step = lockedStep(context);
        if (step == null || !step.holdsLease(executionToken)) {
            return leaseLost(context);
        }

        String output;
        try {
            output = serialize(result);
        } catch (AgentStepExecutionException ex) {
            return recordFailure(step, context, message, ex.getFailureType(), ex.getMessage());
        }

        step.succeed(output);
        step.releaseLease();
        steps.save(step);

        if (result.disposition() == AgentStepResult.Disposition.ADVANCE) {
            if (result.nextState() == null) {
                throw new AgentStepExecutionException(
                        AgentFailureType.INTERNAL_ERROR, "Advancing Agent step result requires nextState");
            }
            publisher.schedule(context.agentRunId(), step.getSequenceNo() + 1, result.nextState(), context.traceId());
        }
        return ExecutionOutcome.SUCCEEDED;
    }

    @Transactional
    public ExecutionOutcome fail(
            AgentStepExecutionContext context,
            String executionToken,
            AgentStepMessage message,
            AgentFailureType failureType,
            String reason
    ) {
        AgentStep step = lockedStep(context);
        if (step == null || !step.holdsLease(executionToken)) {
            return leaseLost(context);
        }
        return recordFailure(step, context, message, failureType, reason);
    }

    private ExecutionOutcome recordFailure(
            AgentStep step,
            AgentStepExecutionContext context,
            AgentStepMessage message,
            AgentFailureType failureType,
            String reason
    ) {
        step.fail(reason);
        step.releaseLease();
        steps.save(step);

        if ((failureType == AgentFailureType.RETRYABLE_INFRASTRUCTURE
                || failureType == AgentFailureType.RETRYABLE_PROVIDER_ERROR)
                && message.attempt() < maxRetry) {
            publisher.scheduleRetry(message);
            return ExecutionOutcome.RETRY_SCHEDULED;
        }

        AgentRun run = runs.findById(context.agentRunId())
                .orElseThrow(() -> new IllegalStateException("Agent run disappeared: " + context.agentRunId()));
        AgentRunStatus terminal =
                failureType == AgentFailureType.CANCELED ? AgentRunStatus.CANCELED : AgentRunStatus.FAILED;
        if (run.getStatus() != terminal) {
            stateMachine.requireTransition(run.getStatus(), terminal);
            run.advanceTo(terminal, step.getSequenceNo());
        }
        return ExecutionOutcome.FAILED;
    }

    private AgentStep lockedStep(AgentStepExecutionContext context) {
        return steps.findForUpdate(context.agentRunId(), context.sequenceNo()).orElse(null);
    }

    private ExecutionOutcome leaseLost(AgentStepExecutionContext context) {
        // The watchdog reclaimed the step while this worker was still busy, and something else has
        // taken it over. Dropping the result is the entire purpose of the token.
        log.warn("Discarding result for Agent step {}/{}: execution lease no longer held",
                context.agentRunId(), context.sequenceNo());
        return ExecutionOutcome.LEASE_LOST;
    }

    private String serialize(AgentStepResult result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            if (json.getBytes(StandardCharsets.UTF_8).length > 8_000) {
                throw new AgentStepExecutionException(
                        AgentFailureType.INTERNAL_ERROR, "Agent step executor output exceeds 8000 bytes");
            }
            return json;
        } catch (JsonProcessingException ex) {
            throw new AgentStepExecutionException(
                    AgentFailureType.INTERNAL_ERROR, "Agent step executor output is not JSON serializable", ex);
        }
    }
}
