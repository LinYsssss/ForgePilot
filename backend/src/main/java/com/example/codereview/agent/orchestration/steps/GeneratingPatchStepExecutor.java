package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.run.AgentRunStatus;
import org.springframework.stereotype.Component;

@Component
public final class GeneratingPatchStepExecutor extends AbstractCheckpointAgentStepExecutor {
    public GeneratingPatchStepExecutor() {
        super(AgentRunStatus.GENERATING_PATCH);
    }
}
