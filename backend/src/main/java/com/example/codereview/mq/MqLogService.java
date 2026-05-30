package com.example.codereview.mq;

import com.example.codereview.config.RabbitMqConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class MqLogService {

    private final MqTaskLogRepository logs;
    private final ObjectMapper objectMapper;

    public MqLogService(MqTaskLogRepository logs, ObjectMapper objectMapper) {
        this.logs = logs;
        this.objectMapper = objectMapper;
    }

    public void published(ReviewTaskMessage message, String routingKey) {
        save(message, routingKey, "PUBLISHED", null);
    }

    public void consumed(ReviewTaskMessage message) {
        save(message, RabbitMqConfig.REVIEW_TASK_ROUTING_KEY, "CONSUMED", null);
    }

    public void failed(ReviewTaskMessage message, String error) {
        save(message, RabbitMqConfig.REVIEW_TASK_ROUTING_KEY, "FAILED", error);
    }

    public void dead(ReviewTaskMessage message, String error) {
        save(message, RabbitMqConfig.REVIEW_DEAD_ROUTING_KEY, "DEAD", error);
    }

    private void save(ReviewTaskMessage message, String routingKey, String status, String error) {
        logs.save(new MqTaskLog(
                message.taskId(),
                message.messageId(),
                RabbitMqConfig.REVIEW_EXCHANGE,
                routingKey,
                queueName(routingKey),
                payload(message),
                status,
                message.retryCount(),
                error
        ));
    }

    private String queueName(String routingKey) {
        if (RabbitMqConfig.REVIEW_DEAD_ROUTING_KEY.equals(routingKey)) {
            return RabbitMqConfig.REVIEW_DEAD_QUEUE;
        }
        return RabbitMqConfig.REVIEW_TASK_QUEUE;
    }

    private String payload(ReviewTaskMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException ex) {
            return message.toString();
        }
    }
}
