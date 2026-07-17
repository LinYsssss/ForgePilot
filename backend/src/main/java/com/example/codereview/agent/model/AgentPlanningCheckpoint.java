package com.example.codereview.agent.model;

import java.math.BigDecimal;

public record AgentPlanningCheckpoint(
        String version,
        StructuredModelResponse toolRequestPlan,
        int modelCalls,
        long inputTokens,
        long outputTokens,
        long latencyMs,
        BigDecimal estimatedCost
) {
    public AgentPlanningCheckpoint {
        if (!"agent-planning-checkpoint-v1".equals(version)) {
            throw new IllegalArgumentException("unsupported planning checkpoint version");
        }
    }
}
