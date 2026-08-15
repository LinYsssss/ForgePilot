package com.example.codereview.agent.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 每轮发布前的清理:回收持有者已死的事件,并让重试耗尽的事件退场。
 *
 * <p>与 {@link AgentOutboxPublisher} 分开,是因为这两件事都是不碰 broker 的纯短事务,
 * 而且这里失败绝不能把发布也一起拖停。
 */
@Service
public class AgentOutboxMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(AgentOutboxMaintenanceService.class);
    private static final String LEASE_EXPIRED_ERROR = "claim lease expired before the publish completed";

    private final AgentOutboxRepository repository;
    private final Clock clock;
    private final Duration retryDelay;
    private final int maxAttempts;

    @Autowired
    public AgentOutboxMaintenanceService(
            AgentOutboxRepository repository,
            @Value("${app.agent.outbox.retry-delay:10s}") Duration retryDelay,
            @Value("${app.agent.outbox.max-attempts:8}") int maxAttempts
    ) {
        this(repository, Clock.systemUTC(), retryDelay, maxAttempts);
    }

    public AgentOutboxMaintenanceService(
            AgentOutboxRepository repository, Clock clock, Duration retryDelay, int maxAttempts) {
        this.repository = repository;
        this.clock = clock;
        this.retryDelay = retryDelay;
        this.maxAttempts = maxAttempts;
    }

    /** @return 有多少卡住的事件被交回待发池。 */
    public int requeueExpiredLeases() {
        Instant now = clock.instant();
        int requeued = repository.requeueExpiredLeases(now, now.plus(retryDelay), LEASE_EXPIRED_ERROR);
        if (requeued > 0) {
            log.warn("Requeued {} Agent outbox event(s) whose claim lease had expired", requeued);
        }
        return requeued;
    }

    /** @return 有多少事件被移入终态 FAILED。 */
    public int failExhausted() {
        int failed = repository.failExhausted(clock.instant(), maxAttempts);
        if (failed > 0) {
            log.error("Gave up on {} Agent outbox event(s) after {} attempts; operator action needed",
                    failed, maxAttempts);
        }
        return failed;
    }
}
