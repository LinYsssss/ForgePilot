package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.orchestration.AgentStepExecutor;
import com.example.codereview.agent.orchestration.AgentStepResult;
import com.example.codereview.agent.run.AgentRunStatus;

abstract class AbstractCheckpointAgentStepExecutor implements AgentStepExecutor {

    private final AgentRunStatus state;

    AbstractCheckpointAgentStepExecutor(AgentRunStatus state) {
        this.state = state;
    }

    @Override
    public final AgentRunStatus state() {
        return state;
    }

    @Override
    public AgentStepResult execute(AgentStepExecutionContext context) {
        if (context.stepType() != state) {
            throw new IllegalArgumentException("Executor state does not match context");
        }
        return AgentStepResult.checkpoint(state);
    }
}
