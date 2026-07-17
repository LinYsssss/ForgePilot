package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.run.AgentRunStatus;
import org.springframework.stereotype.Component;

@Component
public final class VerifyingFindingsStepExecutor extends AbstractCheckpointAgentStepExecutor {
    public VerifyingFindingsStepExecutor() {
        super(AgentRunStatus.VERIFYING_FINDINGS);
    }
}
