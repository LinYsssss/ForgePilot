package com.example.codereview.agent.queue;

import com.example.codereview.agent.error.AgentFailureType;
import java.util.Objects;

public class AgentStepExecutionException extends RuntimeException {

    private final AgentFailureType failureType;

    public AgentStepExecutionException(AgentFailureType failureType, String message) {
        super(message);
        this.failureType = Objects.requireNonNull(failureType, "failureType");
    }

    public AgentStepExecutionException(AgentFailureType failureType, String message, Throwable cause) {
        super(message, cause);
        this.failureType = Objects.requireNonNull(failureType, "failureType");
    }

    public AgentFailureType getFailureType() {
        return failureType;
    }
}
