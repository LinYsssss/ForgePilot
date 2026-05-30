package com.example.codereview.mq;

import java.time.Instant;

public final class MqLogDtos {

    private MqLogDtos() {
    }

    public record MqLogResponse(
            Long id,
            Long taskId,
            String messageId,
            String exchangeName,
            String routingKey,
            String queueName,
            String status,
            int retryCount,
            String errorMessage,
            Instant createdAt
    ) {
        public static MqLogResponse from(MqTaskLog log) {
            return new MqLogResponse(
                    log.getId(),
                    log.getTaskId(),
                    log.getMessageId(),
                    log.getExchangeName(),
                    log.getRoutingKey(),
                    log.getQueueName(),
                    log.getStatus(),
                    log.getRetryCount(),
                    log.getErrorMessage(),
                    log.getCreatedAt()
            );
        }
    }
}
