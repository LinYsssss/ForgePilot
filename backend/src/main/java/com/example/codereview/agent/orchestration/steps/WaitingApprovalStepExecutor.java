package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.run.AgentRunStatus;
import org.springframework.stereotype.Component;

@Component
public final class WaitingApprovalStepExecutor extends AbstractCheckpointAgentStepExecutor {
    public WaitingApprovalStepExecutor() {
        super(AgentRunStatus.WAITING_APPROVAL);
    }
}
