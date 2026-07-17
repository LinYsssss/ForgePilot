package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.run.AgentRunStatus;
import org.springframework.stereotype.Component;

@Component
public final class ExecutingToolsStepExecutor extends AbstractCheckpointAgentStepExecutor {
    public ExecutingToolsStepExecutor() {
        super(AgentRunStatus.EXECUTING_TOOLS);
    }
}
