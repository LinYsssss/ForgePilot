package com.example.codereview.agent.queue;

import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.orchestration.AgentStepExecutor;
import com.example.codereview.agent.orchestration.AgentStepExecutorRegistry;
import com.example.codereview.agent.orchestration.AgentStepResult;
import org.springframework.stereotype.Component;

@Component
public class AgentStepHandler {

    private final AgentStepExecutorRegistry executors;

    public AgentStepHandler(AgentStepExecutorRegistry executors) {
        this.executors = executors;
    }

    public AgentStepResult execute(AgentStepExecutionContext context) {
        AgentStepExecutor executor = executors.require(context.stepType());
        AgentStepResult result = executor.execute(context);
        if (result == null || result.state() != context.stepType()) {
            throw new IllegalStateException("Agent step executor returned an invalid state result");
        }
        return result;
    }
}
