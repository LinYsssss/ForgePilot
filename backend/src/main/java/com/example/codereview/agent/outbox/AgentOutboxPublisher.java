package com.example.codereview.agent.outbox;

import com.example.codereview.config.RabbitMqConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
public class AgentOutboxPublisher {

    private final AgentOutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final Clock clock;
    private final Duration retryDelay;
    private final int maxErrorLength;

    @Autowired
    public AgentOutboxPublisher(
            AgentOutboxRepository repository,
            RabbitTemplate rabbitTemplate,
            @Value("${app.agent.outbox.retry-delay:10s}") Duration retryDelay,
            @Value("${app.agent.outbox.max-error-length:2000}") int maxErrorLength
    ) {
        this(repository, rabbitTemplate, Clock.systemUTC(), retryDelay, maxErrorLength);
    }

    public AgentOutboxPublisher(
            AgentOutboxRepository repository,
            RabbitTemplate rabbitTemplate,
            Clock clock,
            Duration retryDelay,
            int maxErrorLength
    ) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.clock = clock;
        this.retryDelay = retryDelay;
        this.maxErrorLength = maxErrorLength;
    }

    public int publishAvailable(int limit) {
        if (limit <= 0) {
            return 0;
        }
        Instant now = clock.instant();
        List<Long> candidates = repository.findAvailableIds(now, PageRequest.of(0, limit));
        int published = 0;
        for (Long id : candidates) {
            if (repository.claim(id, now) != 1) {
                continue;
            }
            AgentOutboxEvent event = repository.findById(id).orElseThrow();
            try {
                rabbitTemplate.convertAndSend(
                        RabbitMqConfig.AGENT_EXCHANGE,
                        routingKey(event.getEventType()),
                        event.getPayload()
                );
                event.markSent(clock.instant());
                repository.saveAndFlush(event);
                published++;
            } catch (RuntimeException exception) {
                event.markFailed(clock.instant(), retryDelay, failureMessage(exception), maxErrorLength);
                repository.saveAndFlush(event);
            }
        }
        return published;
    }

    private String routingKey(String eventType) {
        return switch (eventType) {
            case "AGENT_STEP" -> RabbitMqConfig.AGENT_STEP_ROUTING_KEY;
            case "AGENT_STEP_DELAY" -> RabbitMqConfig.AGENT_DELAY_ROUTING_KEY;
            case "AGENT_CANCEL" -> RabbitMqConfig.AGENT_CANCEL_ROUTING_KEY;
            case "AGENT_DEAD" -> RabbitMqConfig.AGENT_DEAD_ROUTING_KEY;
            default -> throw new IllegalArgumentException("Unsupported Agent outbox event type: " + eventType);
        };
    }
    private String failureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getName() : message;
    }
}
