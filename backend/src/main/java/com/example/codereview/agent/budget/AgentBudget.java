package com.example.codereview.agent.budget;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

public record AgentBudget(
        Duration maxElapsed,
        int maxToolCalls,
        int maxModelCalls,
        long maxInputTokens,
        long maxOutputTokens,
        BigDecimal maxEstimatedCost
) {
    public AgentBudget {
        Objects.requireNonNull(maxElapsed, "maxElapsed");
        Objects.requireNonNull(maxEstimatedCost, "maxEstimatedCost");
        if (maxElapsed.isNegative() || maxToolCalls < 0 || maxModelCalls < 0
                || maxInputTokens < 0 || maxOutputTokens < 0
                || maxEstimatedCost.signum() < 0) {
            throw new IllegalArgumentException("Agent budget limits must be non-negative");
        }
    }
}
