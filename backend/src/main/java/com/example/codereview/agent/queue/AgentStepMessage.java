package com.example.codereview.agent.queue;

public record AgentStepMessage(
        Long agentRunId,
        int sequenceNo,
        int attempt,
        String traceId
) {
    public AgentStepMessage {
        if (agentRunId == null || agentRunId <= 0) {
            throw new IllegalArgumentException("agentRunId must be positive");
        }
        if (sequenceNo <= 0) {
            throw new IllegalArgumentException("sequenceNo must be positive");
        }
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative");
        }
    }

    public String identity() {
        return "run:" + agentRunId + ":step:" + sequenceNo + ":attempt:" + attempt;
    }

    public AgentStepMessage nextAttempt() {
        return new AgentStepMessage(agentRunId, sequenceNo, attempt + 1, traceId);
    }
}
