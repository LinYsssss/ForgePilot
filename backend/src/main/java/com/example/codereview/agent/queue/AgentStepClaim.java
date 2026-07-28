package com.example.codereview.agent.queue;

import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.queue.AgentStepExecutionService.ExecutionOutcome;

/**
 * Outcome of trying to take a step for execution.
 *
 * <p>The context is an immutable snapshot taken while the claim transaction was open. Carrying a
 * managed JPA entity out of that transaction and writing to it later is exactly the pattern that
 * forced execution to sit inside one long transaction in the first place.
 */
public record AgentStepClaim(
        AgentStepExecutionContext context,
        String executionToken,
        Long stepId,
        ExecutionOutcome rejection
) {

    public static AgentStepClaim claimed(AgentStepExecutionContext context, String executionToken, Long stepId) {
        return new AgentStepClaim(context, executionToken, stepId, null);
    }

    /** The message should be acknowledged without running anything. */
    public static AgentStepClaim rejected(ExecutionOutcome outcome) {
        return new AgentStepClaim(null, null, null, outcome);
    }

    public boolean isClaimed() {
        return rejection == null;
    }
}
