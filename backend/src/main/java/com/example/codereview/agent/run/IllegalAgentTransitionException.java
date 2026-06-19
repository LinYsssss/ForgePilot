package com.example.codereview.agent.run;

public class IllegalAgentTransitionException extends RuntimeException {

    public IllegalAgentTransitionException(AgentRunStatus from, AgentRunStatus to) {
        super("Illegal Agent transition: " + from + " -> " + to);
    }
}
