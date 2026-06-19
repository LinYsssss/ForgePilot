package com.example.codereview.agent.budget;

import com.example.codereview.agent.error.AgentFailureType;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class BudgetGuard {

    public BudgetCheckResult check(AgentBudget budget, BudgetUsage usage, Instant now) {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(now, "now");

        Duration elapsed = Duration.between(usage.startedAt(), now);
        if (elapsed.compareTo(budget.maxElapsed()) > 0) {
            return exceeded("elapsed time exceeded " + budget.maxElapsed());
        }
        if (usage.toolCalls() > budget.maxToolCalls()) {
            return exceeded("tool calls exceeded " + budget.maxToolCalls());
        }
        if (usage.modelCalls() > budget.maxModelCalls()) {
            return exceeded("model calls exceeded " + budget.maxModelCalls());
        }
        if (usage.inputTokens() > budget.maxInputTokens()) {
            return exceeded("input tokens exceeded " + budget.maxInputTokens());
        }
        if (usage.outputTokens() > budget.maxOutputTokens()) {
            return exceeded("output tokens exceeded " + budget.maxOutputTokens());
        }
        if (usage.estimatedCost().compareTo(budget.maxEstimatedCost()) > 0) {
            return exceeded("estimated cost exceeded " + budget.maxEstimatedCost());
        }
        return new BudgetCheckResult(true, null, null);
    }

    private BudgetCheckResult exceeded(String reason) {
        return new BudgetCheckResult(false, AgentFailureType.BUDGET_EXCEEDED, reason);
    }

    public record BudgetCheckResult(
            boolean allowed,
            AgentFailureType failureType,
            String reason
    ) {
    }
}
