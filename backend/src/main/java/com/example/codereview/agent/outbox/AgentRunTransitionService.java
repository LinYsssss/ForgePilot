package com.example.codereview.agent.outbox;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.run.AgentStateMachine;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRunTransitionService {

    private final AgentRunRepository runs;
    private final AgentOutboxRepository outbox;
    private final AgentStateMachine stateMachine;

    public AgentRunTransitionService(
            AgentRunRepository runs,
            AgentOutboxRepository outbox,
            AgentStateMachine stateMachine
    ) {
        this.runs = runs;
        this.outbox = outbox;
        this.stateMachine = stateMachine;
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
        stateMachine.requireTransition(run.getStatus(), nextStatus);
        run.advanceTo(nextStatus, stepSequence);
        outbox.saveAndFlush(AgentOutboxEvent.pending(
                eventKey,
                run.getId(),
                eventType,
                payload,
                traceId,
                Instant.now()
        ));
    }
}
