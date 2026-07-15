package com.example.codereview.agent.outbox;

import com.example.codereview.agent.observability.AgentMetrics;
import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.run.AgentStateMachine;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRunTransitionService {

    private static final Set<String> EVENT_TYPES = Set.of(
            "AGENT_STEP",
            "AGENT_STEP_DELAY",
            "AGENT_CANCEL",
            "AGENT_DEAD"
    );

    private final AgentRunRepository runs;
    private final AgentOutboxRepository outbox;
    private final AgentStateMachine stateMachine;
    private final AgentMetrics metrics;

    public AgentRunTransitionService(
            AgentRunRepository runs,
            AgentOutboxRepository outbox,
            AgentStateMachine stateMachine,
            AgentMetrics metrics
    ) {
        this.runs = runs;
        this.outbox = outbox;
        this.stateMachine = stateMachine;
        this.metrics = metrics;
    }

    @Transactional
    public void transitionAndEnqueue(
            Long agentRunId,
            AgentRunStatus nextStatus,
            int stepSequence,
            String eventKey,
            String payload,
            String traceId
    ) {
        transitionAndEnqueue(
                agentRunId, nextStatus, stepSequence, eventKey, "AGENT_STEP", payload, traceId
        );
    }

    @Transactional
    public void transitionAndEnqueue(
            Long agentRunId,
            AgentRunStatus nextStatus,
            int stepSequence,
            String eventKey,
            String eventType,
            String payload,
            String traceId
    ) {
        AgentRun run = runs.findById(agentRunId)
                .orElseThrow(() -> new IllegalArgumentException("Agent run not found: " + agentRunId));
        AgentRunStatus from = run.getStatus();
        stateMachine.requireTransition(from, nextStatus);
        run.advanceTo(nextStatus, stepSequence);
        saveOutbox(run.getId(), eventKey, eventType, payload, traceId);
        recordLifecycle(from, nextStatus);
    }

    @Transactional
    public void enqueue(
            Long agentRunId,
            String eventKey,
            String eventType,
            String payload,
            String traceId
    ) {
        if (!runs.existsById(agentRunId)) {
            throw new IllegalArgumentException("Agent run not found: " + agentRunId);
        }
        saveOutbox(agentRunId, eventKey, eventType, payload, traceId);
    }

    private void recordLifecycle(AgentRunStatus from, AgentRunStatus to) {
        if (from == AgentRunStatus.RECEIVED) {
            metrics.runCreated();
        }
        if (to == AgentRunStatus.COMPLETED) {
            metrics.runCompleted();
        } else if (to == AgentRunStatus.FAILED) {
            metrics.runFailed();
        }
    }

    private void saveOutbox(
            Long agentRunId,
            String eventKey,
            String eventType,
            String payload,
            String traceId
    ) {
        if (!EVENT_TYPES.contains(eventType)) {
            throw new IllegalArgumentException("Unsupported Agent outbox event type: " + eventType);
        }
        outbox.saveAndFlush(AgentOutboxEvent.pending(
                eventKey,
                agentRunId,
                eventType,
                payload,
                traceId,
                Instant.now()
        ));
    }
}
