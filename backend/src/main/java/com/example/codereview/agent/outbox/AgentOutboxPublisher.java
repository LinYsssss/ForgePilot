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
 * 分三段排空 outbox:短事务抢占事件 → 不持事务调用 broker → 第二个短事务按 claim token
 * 做 CAS 写回结果。
 *
 * <p>关键性质:只有 RabbitMQ 确认过、且没有以 unroutable 退回的事件才会被标成 SENT。
 * 乐观发布(发完立刻标已发)会造出一个「声称投递成功、而 broker 根本没持久化」的数据库。
 *
 * <p>投递语义天然是 at-least-once:确认超时的消息 broker 仍可能已经落盘,该事件会被重发,
 * 因此消费侧必须保持幂等。
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

    /** @return 本轮真正进入 SENT 状态的事件数。 */
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
            // 有人抢先认领了,或这条事件已经不到期。
            return false;
        }

        AgentOutboxEvent event = repository.findById(id).orElse(null);
        if (event == null) {
            return false;
        }

        // 这一段刻意不开事务:broker 往返可能耗时数秒,不能让它占着数据库连接与行锁。
        PublishOutcome outcome = send(event);
        Instant completedAt = clock.instant();

        if (outcome.success()) {
            return repository.markSent(id, claimToken, completedAt) == 1;
        }
        int updated = repository.markRetry(
                id, claimToken, completedAt, completedAt.plus(retryDelay), truncate(outcome.error()));
        if (updated == 0) {
            // 发布期间租约被回收方抢走:它已经把事件重新排队,这里拿到的结果是过期的。
            // 丢弃它正是 claim token 存在的意义。
            log.warn("Outbox event {} lost its lease before the failure could be recorded", id);
        }
        return false;
    }

    private PublishOutcome send(AgentOutboxEvent event) {
        String routingKey;
        try {
            routingKey = routingKey(event.getEventType());
        } catch (IllegalArgumentException ex) {
            // 这条永远路由不出去;让它把重试次数耗尽后落进 FAILED,而不是假装投递成功。
            return PublishOutcome.rejected(ex.getMessage());
        }

        CorrelationData correlation = new CorrelationData(event.getEventKey());
        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.AGENT_EXCHANGE, routingKey, event.getPayload(), correlation);
        } catch (RuntimeException exception) {
            return PublishOutcome.rejected(failureMessage(exception));
        }

        if (!publisherConfirmsEnabled()) {
            // 没有 publisher confirms 就观测不到 broker 是否真的收下了。这条是本地开发与
            // 单测路径;生产在 app-agent.yml 里开启 confirms,让 SENT 状态真正有意义。
            return PublishOutcome.acknowledged();
        }
        return awaitConfirmation(event, correlation);
    }

    private PublishOutcome awaitConfirmation(AgentOutboxEvent event, CorrelationData correlation) {
        try {
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            // 同一条消息的 return 一定早于 confirm 到达,所以在这里查是安全的:
            // 路由不到队列的消息 broker 照样会 ack,只是它从未进过任何队列。
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
            // broker 之后仍可能把它落盘,这正是消费侧必须幂等的原因。
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
