package com.example.codereview.agent.queue;

import org.springframework.stereotype.Component;

@Component
public class AgentStepHandler {

    public String execute(AgentStepMessage message) {
        return "Agent step accepted: " + message.identity();
    }
}
