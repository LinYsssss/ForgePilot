package com.example.codereview.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.codereview.agent.budget.BudgetGuard;
import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentModelBudgetPolicyTest {

    @Test
    void aggregatesConversationTurnsAndCalculatesCost() {
        AgentModelBudgetPolicy policy = new AgentModelBudgetPolicy(
                new BudgetGuard(), 1000, 4, 1000, 500,
                new BigDecimal("1.00"), new BigDecimal("2.00"), new BigDecimal("4.00")
        );

        var usage = policy.requireWithinBudget(List.of(turn(1, 100, 20, 10), turn(1, 200, 30, 20)));

        assertThat(usage.modelCalls()).isEqualTo(2);
        assertThat(usage.inputTokens()).isEqualTo(300);
        assertThat(usage.outputTokens()).isEqualTo(50);
        assertThat(usage.estimatedCost()).isEqualByComparingTo("0.00080000");
    }

    @Test
    void rejectsConversationWhenAnyAggregateLimitIsExceeded() {
        AgentModelBudgetPolicy policy = new AgentModelBudgetPolicy(
                new BudgetGuard(), 1000, 1, 100, 100,
                new BigDecimal("0.0001"), BigDecimal.ONE, BigDecimal.ONE
        );

        assertThatThrownBy(() -> policy.requireWithinBudget(List.of(turn(2, 200, 20, 10))))
                .isInstanceOf(AgentStepExecutionException.class)
                .satisfies(error -> assertThat(((AgentStepExecutionException) error).getFailureType())
                        .isEqualTo(AgentFailureType.BUDGET_EXCEEDED));
    }

    private StructuredAgentModelService.ModelGenerationResult turn(
            int calls, long input, long output, long latency
    ) {
        return new StructuredAgentModelService.ModelGenerationResult(
                new ModelOutputValidator.ValidationResult(true, null, null, null),
                calls, input, output, latency
        );
    }
}
