package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.run.AgentRunStatus;
import org.springframework.stereotype.Component;

@Component
public final class RetrievingContextStepExecutor extends AbstractCheckpointAgentStepExecutor {
    public RetrievingContextStepExecutor() {
        super(AgentRunStatus.RETRIEVING_CONTEXT);
    }
}
