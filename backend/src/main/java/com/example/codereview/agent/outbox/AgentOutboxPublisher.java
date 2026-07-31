package com.example.codereview.agent.outbox;

import com.example.codereview.config.RabbitMqConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Drains the outbox in three separate phases: a short transaction claims the event, the broker call
 * happens with no transaction open, and a second short transaction writes the outcome back under a
 * compare-and-set on the claim token.
 *
 * <p>The important property is that an event is only marked SENT once RabbitMQ has confirmed it and
 * has not returned it as unroutable. Publishing optimistically — send, then immediately mark sent —
 * produces a database that claims delivery for messages the broker never persisted.
 *
 * <p>Delivery is at-least-once by construction: a confirm that times out may still have been
 * persisted by the broker, and the event will be republished. Consumers must stay idempotent.
 */
@Component
public class AgentOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(AgentOutboxPublisher.class);

    private final AgentOutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final Clock clock;
    private final Duration retryDelay;
    private final int maxErrorLength;
    private final Duration leaseDuration;
    private final Duration confirmTimeout;

    @Autowired
    public AgentOutboxPublisher(
            AgentOutboxRepository repository,
            RabbitTemplate rabbitTemplate,
            @Value("${app.agent.outbox.retry-delay:10s}") Duration retryDelay,
            @Value("${app.agent.outbox.max-error-length:2000}") int maxErrorLength,
            @Value("${app.agent.outbox.lease-duration:60s}") Duration leaseDuration,
            @Value("${app.agent.outbox.confirm-timeout:10s}") Duration confirmTimeout
    ) {
        this(repository, rabbitTemplate, Clock.systemUTC(), retryDelay, maxErrorLength, leaseDuration, confirmTimeout);
    }

    public AgentOutboxPublisher(
            AgentOutboxRepository repository,
            RabbitTemplate rabbitTemplate,
            Clock clock,
            Duration retryDelay,
            int maxErrorLength
    ) {
        this(repository, rabbitTemplate, clock, retryDelay, maxErrorLength, Duration.ofSeconds(60),
                Duration.ofSeconds(10));
    }

    public AgentOutboxPublisher(
            AgentOutboxRepository repository,
            RabbitTemplate rabbitTemplate,
            Clock clock,
            Duration retryDelay,
            int maxErrorLength,
            Duration leaseDuration,
            Duration confirmTimeout
    ) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.clock = clock;
        this.retryDelay = retryDelay;
        this.maxErrorLength = maxErrorLength;
        this.leaseDuration = leaseDuration;
        this.confirmTimeout = confirmTimeout;
    }

    /** @return how many events reached the SENT state in this pass. */
    public int publishAvailable(int limit) {
        if (limit <= 0) {
            return 0;
        }
        List<Long> candidates = repository.findAvailableIds(clock.instant(), PageRequest.of(0, limit));
        int published = 0;
        for (Long id : candidates) {
            if (publishOne(id)) {
                published++;
            }
        }
        return published;
    }

    private boolean publishOne(Long id) {
        Instant claimedAt = clock.instant();
        String claimToken = UUID.randomUUID().toString();
        if (repository.claim(id, claimedAt, claimToken, claimedAt.plus(leaseDuration)) != 1) {
            // Somebody else got there first, or the event is no longer due.
            return false;
        }

        AgentOutboxEvent event = repository.findById(id).orElse(null);
        if (event == null) {
            return false;
        }

        // No transaction is open across this call: the broker round trip can take seconds and must
        // not hold a database connection or row lock.
        PublishOutcome outcome = send(event);
        Instant completedAt = clock.instant();

        if (outcome.success()) {
            return repository.markSent(id, claimToken, completedAt) == 1;
        }
        int updated = repository.markRetry(
                id, claimToken, completedAt, completedAt.plus(retryDelay), truncate(outcome.error()));
        if (updated == 0) {
            // The lease was reaped while we were publishing; the reaper already requeued the event
            // and this result is stale. Dropping it is the whole point of the claim token.
            log.warn("Outbox event {} lost its lease before the failure could be recorded", id);
        }
        return false;
    }

    private PublishOutcome send(AgentOutboxEvent event) {
        String routingKey;
        try {
            routingKey = routingKey(event.getEventType());
        } catch (IllegalArgumentException ex) {
            // Nothing will ever route this; let it burn through its retries into FAILED rather than
            // pretending it was delivered.
            return PublishOutcome.rejected(ex.getMessage());
        }

        CorrelationData correlation = new CorrelationData(event.getEventKey());
        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.AGENT_EXCHANGE, routingKey, event.getPayload(), correlation);
        } catch (RuntimeException exception) {
            return PublishOutcome.rejected(failureMessage(exception));
        }

        if (!publisherConfirmsEnabled()) {
            // Without confirms the broker's acceptance cannot be observed. This is the local
            // development and unit-test path; production enables confirms (see app-agent.yml) so
            // the SENT state actually means something.
            return PublishOutcome.acknowledged();
        }
        return awaitConfirmation(event, correlation);
    }

    private PublishOutcome awaitConfirmation(AgentOutboxEvent event, CorrelationData correlation) {
        try {
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            // A return always arrives before the confirm for the same message, so checking it here
            // is safe: an unroutable message is acked by the broker but never reached a queue.
            if (correlation.getReturned() != null) {
                return PublishOutcome.rejected("message returned as unroutable");
            }
            if (confirm == null) {
                return PublishOutcome.rejected("broker confirm was empty");
            }
            if (!confirm.isAck()) {
                String reason = confirm.getReason();
                return PublishOutcome.rejected("broker nacked the message" + (reason == null ? "" : ": " + reason));
            }
            return PublishOutcome.acknowledged();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return PublishOutcome.rejected("interrupted while waiting for broker confirm");
        } catch (java.util.concurrent.TimeoutException ex) {
            // The broker may still persist it later, which is exactly why redelivery has to be
            // idempotent on the consumer side.
            log.warn("Outbox event {} timed out waiting for a broker confirm", event.getId());
            return PublishOutcome.rejected("timed out waiting for broker confirm");
        } catch (java.util.concurrent.ExecutionException ex) {
            return PublishOutcome.rejected(failureMessage(ex));
        }
    }

    private boolean publisherConfirmsEnabled() {
        try {
            var connectionFactory = rabbitTemplate.getConnectionFactory();
            return connectionFactory != null && connectionFactory.isPublisherConfirms();
        } catch (RuntimeException ex) {
            return false;
        }
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

    private String failureMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getName() : message;
    }

    private String truncate(String value) {
        if (value == null || maxErrorLength <= 0) {
            return null;
        }
        return value.substring(0, Math.min(value.length(), maxErrorLength));
    }

    private record PublishOutcome(boolean success, String error) {

        static PublishOutcome acknowledged() {
            return new PublishOutcome(true, null);
        }

        static PublishOutcome rejected(String error) {
            return new PublishOutcome(false, error);
        }
    }
}
