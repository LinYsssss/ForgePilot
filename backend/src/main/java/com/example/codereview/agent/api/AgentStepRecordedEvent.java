package com.example.codereview.agent.api;

/**
 * Published inside the transaction that persists an Agent step change so that {@link AgentEventService}
 * can fan the change out to live SSE subscribers only after the transaction commits. The event carries
 * identifiers only; subscribers re-read the authoritative row from the database.
 */
public record AgentStepRecordedEvent(Long agentRunId, int sequenceNo) {

    public AgentStepRecordedEvent {
        if (agentRunId == null) {
            throw new IllegalArgumentException("agentRunId must not be null");
        }
    }
}
