package com.example.codereview.agent.orchestration;

import com.example.codereview.agent.run.AgentRunStatus;

public interface AgentStepExecutor {

    AgentRunStatus state();

    AgentStepResult execute(AgentStepExecutionContext context);
}
