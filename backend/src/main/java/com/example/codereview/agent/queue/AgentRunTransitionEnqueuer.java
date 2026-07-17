package com.example.codereview.agent.queue;

import com.example.codereview.agent.outbox.AgentRunTransitionService;
import com.example.codereview.agent.run.AgentStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class AgentRunTransitionEnqueuer {
    private final AgentRunTransitionService transitions;
    private final ObjectMapper mapper;

    public AgentRunTransitionEnqueuer(AgentRunTransitionService transitions, ObjectMapper mapper) {
        this.transitions = transitions;
        this.mapper = mapper;
    }

    public void enqueue(AgentStep step, String eventKey) {
        try {
            AgentStepMessage message = new AgentStepMessage(
                    step.getAgentRunId(), step.getSequenceNo(), step.getAttempt(),
                    "approval-wakeup:" + step.getAgentRunId() + ":" + step.getSequenceNo()
            );
            transitions.enqueue(step.getAgentRunId(), eventKey, "AGENT_STEP",
                    mapper.writeValueAsString(message), message.traceId());
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to enqueue approval wakeup", ex);
        }
    }
}
