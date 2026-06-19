package com.example.codereview.agent.tool;

public record ToolContext(
        Long agentRunId,
        Long agentStepId,
        String invocationKey,
        boolean approved,
        String correlationId
) {
}
