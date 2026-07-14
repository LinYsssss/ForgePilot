package com.example.codereview.agent.queue;

import com.example.codereview.config.RabbitMqConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class AgentStepConsumer {

    private final ObjectMapper objectMapper;
    private final AgentStepExecutionService executionService;

    public AgentStepConsumer(ObjectMapper objectMapper, AgentStepExecutionService executionService) {
        this.objectMapper = objectMapper;
        this.executionService = executionService;
    }

    @RabbitListener(queues = RabbitMqConfig.AGENT_STEP_QUEUE)
    public void consume(String payload) {
        try {
            consume(objectMapper.readValue(payload, AgentStepMessage.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid Agent step message", exception);
        }
    }

    public AgentStepExecutionService.ExecutionOutcome consume(AgentStepMessage message) {
        return executionService.execute(message);
    }
}
