package com.example.codereview.agent.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 补上原先缺的那一环:生产里真正负责排空 outbox 的东西。
 *
 * <p>没有它时 {@code publishAvailable} 只会被测试调到,于是 Agent run 入队第一条事件后
 * 就永远停在 PENDING。
 *
 * <p>调度基础设施来自 {@code AgentSchedulingConfig},与本类由同一个开关门控,测试上下文默认关闭。
 * 注意「关闭」只对没打开它的上下文成立:一旦某个测试类显式打开调度,Spring 会把那个上下文
 * 留在缓存里,其调度线程在该类结束后仍继续动同一张表——所以打开调度的测试类必须
 * {@code @DirtiesContext}(见 AgentOutboxSchedulerTest)。
 */
@Component
@ConditionalOnProperty(value = "app.agent.scheduling.enabled", havingValue = "true")
public class AgentOutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentOutboxScheduler.class);

    private final AgentOutboxMaintenanceService maintenance;
    private final AgentOutboxPublisher publisher;
    private final int batchSize;

    public AgentOutboxScheduler(
            AgentOutboxMaintenanceService maintenance,
            AgentOutboxPublisher publisher,
            @Value("${app.agent.outbox.batch-size:50}") int batchSize
    ) {
        this.maintenance = maintenance;
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    /**
     * 先回收再发布:worker 死掉的事件应该在同一轮里重新变得可发,而不是再等一个 tick。
     *
     * <p>整个 tick 都被兜住——有些执行器遇到抛异常的定时方法就不再重新调度它,
     * 为一次瞬时数据库错误丢掉整条排空回路,正好会重演本类要修的那个故障。
     */
    @Scheduled(
            fixedDelayString = "${app.agent.outbox.fixed-delay-ms:1000}",
            initialDelayString = "${app.agent.outbox.initial-delay-ms:5000}")
    public void tick() {
        try {
            maintenance.requeueExpiredLeases();
        } catch (RuntimeException ex) {
            log.warn("Outbox lease reclamation failed; will retry next tick", ex);
        }
        try {
            maintenance.failExhausted();
        } catch (RuntimeException ex) {
            log.warn("Outbox retirement of exhausted events failed; will retry next tick", ex);
        }
        try {
            publisher.publishAvailable(batchSize);
        } catch (RuntimeException ex) {
            log.warn("Outbox publish pass failed; will retry next tick", ex);
        }
    }
}
