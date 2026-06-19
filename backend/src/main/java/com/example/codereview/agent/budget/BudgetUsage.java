package com.example.codereview.agent.budget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record BudgetUsage(
        Instant startedAt,
        int toolCalls,
        int modelCalls,
        long inputTokens,
        long outputTokens,
        BigDecimal estimatedCost
) {
    public BudgetUsage {
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(estimatedCost, "estimatedCost");
        if (toolCalls < 0 || modelCalls < 0 || inputTokens < 0 || outputTokens < 0
                || estimatedCost.signum() < 0) {
            throw new IllegalArgumentException("Budget usage must be non-negative");
        }
    }
}
