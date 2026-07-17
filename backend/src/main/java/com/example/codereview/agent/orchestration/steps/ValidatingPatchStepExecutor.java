package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.run.AgentRunStatus;
import org.springframework.stereotype.Component;

@Component
public final class ValidatingPatchStepExecutor extends AbstractCheckpointAgentStepExecutor {
    public ValidatingPatchStepExecutor() {
        super(AgentRunStatus.VALIDATING_PATCH);
    }
}
