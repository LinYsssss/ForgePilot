package com.example.codereview.agent.model;

import com.example.codereview.agent.budget.AgentBudget;
import com.example.codereview.agent.budget.BudgetGuard;
import com.example.codereview.agent.budget.BudgetUsage;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AgentModelBudgetPolicy {

    private final BudgetGuard guard;
    private final AgentBudget budget;
    private final BigDecimal inputCostPerMillion;
    private final BigDecimal outputCostPerMillion;

    public AgentModelBudgetPolicy(
            BudgetGuard guard,
            @Value("${app.agent.model.max-elapsed-ms:300000}") long maxElapsedMs,
            @Value("${app.agent.model.max-calls:4}") int maxModelCalls,
            @Value("${app.agent.model.max-input-tokens:65536}") long maxInputTokens,
            @Value("${app.agent.model.max-output-tokens:16384}") long maxOutputTokens,
            @Value("${app.agent.model.max-estimated-cost:1.00}") BigDecimal maxEstimatedCost,
            @Value("${app.agent.model.input-cost-per-million:1.00}") BigDecimal inputCostPerMillion,
            @Value("${app.agent.model.output-cost-per-million:3.00}") BigDecimal outputCostPerMillion
    ) {
        this.guard = guard;
        this.budget = new AgentBudget(
                Duration.ofMillis(maxElapsedMs), 0, maxModelCalls,
                maxInputTokens, maxOutputTokens, maxEstimatedCost
        );
        this.inputCostPerMillion = requireNonNegative(inputCostPerMillion, "input cost");
        this.outputCostPerMillion = requireNonNegative(outputCostPerMillion, "output cost");
    }

    public static AgentModelBudgetPolicy defaults() {
        return new AgentModelBudgetPolicy(
                new BudgetGuard(), 300_000, 4, 65_536, 16_384,
                new BigDecimal("1.00"), new BigDecimal("1.00"), new BigDecimal("3.00")
        );
    }

    public UsageSnapshot requireWithinBudget(List<StructuredAgentModelService.ModelGenerationResult> turns) {
        int calls = turns.stream().mapToInt(StructuredAgentModelService.ModelGenerationResult::modelCalls).sum();
        long input = turns.stream().mapToLong(StructuredAgentModelService.ModelGenerationResult::inputTokens).sum();
        long output = turns.stream().mapToLong(StructuredAgentModelService.ModelGenerationResult::outputTokens).sum();
        long latency = turns.stream().mapToLong(StructuredAgentModelService.ModelGenerationResult::latencyMs).sum();
        BigDecimal cost = estimate(input, output);
        var result = guard.check(
                budget,
                new BudgetUsage(
                        Instant.now().minusMillis(latency), 0, calls, input, output, cost
                ),
                Instant.now()
        );
        if (!result.allowed()) {
            throw new AgentStepExecutionException(result.failureType(), result.reason());
        }
        return new UsageSnapshot(calls, input, output, latency, cost);
    }

    public UsageSnapshot requireWithinBudget(UsageSnapshot previous, StructuredAgentModelService.ModelGenerationResult turn) {
        return requireWithinBudget(List.of(new StructuredAgentModelService.ModelGenerationResult(
                turn.validation(),
                previous.modelCalls() + turn.modelCalls(),
                previous.inputTokens() + turn.inputTokens(),
                previous.outputTokens() + turn.outputTokens(),
                previous.latencyMs() + turn.latencyMs()
        )));
    }

    private BigDecimal estimate(long inputTokens, long outputTokens) {
        BigDecimal million = new BigDecimal("1000000");
        return inputCostPerMillion.multiply(BigDecimal.valueOf(inputTokens))
                .add(outputCostPerMillion.multiply(BigDecimal.valueOf(outputTokens)))
                .divide(million, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal requireNonNegative(BigDecimal value, String label) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
        return value;
    }

    public record UsageSnapshot(
            int modelCalls,
            long inputTokens,
            long outputTokens,
            long latencyMs,
            BigDecimal estimatedCost
    ) {
    }
}
