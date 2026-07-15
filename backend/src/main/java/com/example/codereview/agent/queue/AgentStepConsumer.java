package com.example.codereview.agent.queue;

import com.example.codereview.common.web.TraceIdFilter;
import com.example.codereview.config.RabbitMqConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
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
        // Carry the correlation id from the message into MDC so step and tool logs produced while this
        // step runs share the same traceId that started on the inbound HTTP request.
        String previous = MDC.get(TraceIdFilter.TRACE_ID);
        String correlationId = message.traceId();
        boolean applied = correlationId != null && !correlationId.isBlank();
        if (applied) {
            MDC.put(TraceIdFilter.TRACE_ID, correlationId);
        }
        try {
            return executionService.execute(message);
        } finally {
            if (previous != null) {
                MDC.put(TraceIdFilter.TRACE_ID, previous);
            } else if (applied) {
                MDC.remove(TraceIdFilter.TRACE_ID);
            }
        }
    }
}
