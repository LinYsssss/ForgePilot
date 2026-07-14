package com.example.codereview.agent.queue;

import com.example.codereview.agent.api.AgentStepRecordedEvent;
import com.example.codereview.agent.outbox.AgentRunTransitionService;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.run.AgentStep;
import com.example.codereview.agent.run.AgentStepRepository;
import com.example.codereview.agent.run.AgentStepStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AgentStepPublisher {

    private static final String STEP_EVENT = "AGENT_STEP";
    private static final String DELAY_EVENT = "AGENT_STEP_DELAY";

    private final AgentStepRepository steps;
    private final AgentRunTransitionService transitions;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    public AgentStepPublisher(
            AgentStepRepository steps,
            AgentRunTransitionService transitions,
            ObjectMapper objectMapper,
            ApplicationEventPublisher events
    ) {
        this.steps = steps;
        this.transitions = transitions;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    @Transactional
    public AgentStepMessage schedule(
            Long agentRunId,
            int sequenceNo,
            AgentRunStatus stepType,
            String traceId
    ) {
        AgentStepMessage message = new AgentStepMessage(agentRunId, sequenceNo, 0, traceId);
        if (steps.findByAgentRunIdAndSequenceNo(agentRunId, sequenceNo).isPresent()) {
            return message;
        }
        steps.saveAndFlush(AgentStep.pending(agentRunId, sequenceNo, stepType));
        transitions.transitionAndEnqueue(
                agentRunId,
                stepType,
                sequenceNo,
                message.identity(),
                STEP_EVENT,
                serialize(message),
                traceId
        );
        events.publishEvent(new AgentStepRecordedEvent(agentRunId, sequenceNo));
        return message;
    }

    /**
     * Operator-initiated retry of a previously failed/interrupted step. The run must already have been
     * re-opened into {@code RETRY_WAIT} in the same transaction; here we only re-enqueue the step for
     * its next attempt through the outbox (no forward state transition of its own).
     */
    @Transactional
    public AgentStepMessage republishForRetry(AgentStep step) {
        if (step.isSucceeded() || step.getStatus() == AgentStepStatus.RUNNING) {
            throw new IllegalArgumentException(
                    "Only a failed or interrupted Agent step can be retried, was " + step.getStatus()
            );
        }
        int retryAttempt = step.getAttempt() + 1;
        String traceId = "retry:" + step.getAgentRunId() + ":" + step.getSequenceNo() + ":" + retryAttempt;
        AgentStepMessage message = new AgentStepMessage(
                step.getAgentRunId(),
                step.getSequenceNo(),
                retryAttempt,
                traceId
        );
        transitions.enqueue(
                message.agentRunId(),
                message.identity(),
                STEP_EVENT,
                serialize(message),
                traceId
        );
        return message;
    }

    @Transactional
    public AgentStepMessage scheduleRetry(AgentStepMessage previous) {
        AgentStepMessage retry = previous.nextAttempt();
        transitions.transitionAndEnqueue(
                retry.agentRunId(),
                AgentRunStatus.RETRY_WAIT,
                retry.sequenceNo(),
                retry.identity(),
                DELAY_EVENT,
                serialize(retry),
                retry.traceId()
        );
        return retry;
    }

    @Transactional
    public AgentStepMessage republishInterrupted(AgentStep step) {
        if (step.getStatus() != AgentStepStatus.INTERRUPTED) {
            throw new IllegalArgumentException("Only interrupted Agent steps can be recovered");
        }
        int recoveryAttempt = step.getAttempt() + 1;
        String traceId = "recovery:" + step.getAgentRunId() + ":" + step.getSequenceNo()
                + ":" + recoveryAttempt;
        AgentStepMessage message = new AgentStepMessage(
                step.getAgentRunId(),
                step.getSequenceNo(),
                recoveryAttempt,
                traceId
        );
        transitions.enqueue(
                message.agentRunId(),
                message.identity(),
                STEP_EVENT,
                serialize(message),
                traceId
        );
        return message;
    }

    private String serialize(AgentStepMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Agent step message", exception);
        }
    }
}
