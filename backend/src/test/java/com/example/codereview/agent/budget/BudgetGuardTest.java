package com.example.codereview.agent.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.agent.error.AgentFailureType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BudgetGuardTest {

    private final BudgetGuard guard = new BudgetGuard();
    private final Instant startedAt = Instant.parse("2026-06-18T00:00:00Z");
    private final AgentBudget budget = new AgentBudget(
            Duration.ofMinutes(5),
            10,
            4,
            10_000,
            2_000,
            new BigDecimal("0.50")
    );

    @Test
    void allowsUsageWithinEveryLimit() {
        assertThat(guard.check(
                budget,
                usage(9, 3, 9_999, 1_999, "0.49"),
                startedAt.plus(Duration.ofMinutes(5))
        ).allowed()).isTrue();
    }

    @Test
    void rejectsElapsedTimeLimit() {
        assertExceeded(usage(0, 0, 0, 0, "0"), startedAt.plusSeconds(301), "elapsed");
    }

    @Test
    void rejectsToolCallLimit() {
        assertExceeded(usage(11, 0, 0, 0, "0"), startedAt, "tool calls");
    }

    @Test
    void rejectsModelCallLimit() {
        assertExceeded(usage(0, 5, 0, 0, "0"), startedAt, "model calls");
    }

    @Test
    void rejectsInputTokenLimit() {
        assertExceeded(usage(0, 0, 10_001, 0, "0"), startedAt, "input tokens");
    }

    @Test
    void rejectsOutputTokenLimit() {
        assertExceeded(usage(0, 0, 0, 2_001, "0"), startedAt, "output tokens");
    }

    @Test
    void rejectsEstimatedCostLimit() {
        assertExceeded(usage(0, 0, 0, 0, "0.51"), startedAt, "estimated cost");
    }

    private BudgetUsage usage(int tools, int models, long inputTokens, long outputTokens, String cost) {
        return new BudgetUsage(
                startedAt,
                tools,
                models,
                inputTokens,
                outputTokens,
                new BigDecimal(cost)
        );
    }

    private void assertExceeded(BudgetUsage usage, Instant now, String reason) {
        BudgetGuard.BudgetCheckResult result = guard.check(budget, usage, now);
        assertThat(result.allowed()).isFalse();
        assertThat(result.failureType()).isEqualTo(AgentFailureType.BUDGET_EXCEEDED);
        assertThat(result.reason()).contains(reason);
    }
}
