package com.example.codereview.agent.queue;

import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.queue.AgentStepExecutionService.ExecutionOutcome;

/**
 * 尝试认领一个步骤去执行的结果。
 *
 * <p>其中的 context 是**认领事务还开着时**取的不可变快照。把一个受管的 JPA 实体带出那个事务、
 * 之后再往里写,正是当初逼得整个执行不得不待在一个长事务里的那种写法。
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

    /** 该消息应当被 ack 掉,且不执行任何东西。 */
    public static AgentStepClaim rejected(ExecutionOutcome outcome) {
        return new AgentStepClaim(null, null, null, outcome);
    }

    public boolean isClaimed() {
        return rejection == null;
    }
}
