package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.run.AgentRunStatus;
import org.springframework.stereotype.Component;

@Component
public final class PlanningStepExecutor extends AbstractCheckpointAgentStepExecutor {
    public PlanningStepExecutor() {
        super(AgentRunStatus.PLANNING);
    }
}
