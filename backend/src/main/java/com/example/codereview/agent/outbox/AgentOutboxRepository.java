package com.example.codereview.agent.outbox;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 这里每一次状态流转都写成**带条件的批量更新**,而不是「读出来—改—存回去」,让数据库自己裁决谁赢。
 * 发布发生在事务之外、可能耗时数秒;等某个 worker 回写结果时,它的租约可能早已被回收、
 * 事件也已交给了别人。把每次写入都卡在 {@code (id, claim_token, status)} 上,这种情况就会
 * 返回「更新 0 行」,而不是把较新的那次尝试悄悄覆盖掉。
 */
public interface AgentOutboxRepository extends JpaRepository<AgentOutboxEvent, Long> {

    @Query("""
            select event.id
            from AgentOutboxEvent event
            where event.status = com.example.codereview.agent.outbox.AgentOutboxStatus.PENDING
              and event.nextAttemptAt <= :now
            order by event.createdAt, event.id
            """)
    List<Long> findAvailableIds(@Param("now") Instant now, Pageable pageable);

    /**
     * 拿下一条待发事件的所有权。赢家返回 1,其余一律返回 0,因此多个调度器可以安全竞争。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentOutboxEvent event
               set event.status = com.example.codereview.agent.outbox.AgentOutboxStatus.PROCESSING,
                   event.claimedAt = :now,
                   event.claimToken = :claimToken,
                   event.leaseExpiresAt = :leaseExpiresAt,
                   event.updatedAt = :now
             where event.id = :id
               and event.status = com.example.codereview.agent.outbox.AgentOutboxStatus.PENDING
               and event.nextAttemptAt <= :now
            """)
    int claim(
            @Param("id") Long id,
            @Param("now") Instant now,
            @Param("claimToken") String claimToken,
            @Param("leaseExpiresAt") Instant leaseExpiresAt);

    /** 只有在 broker 确认收下、且没有把消息退回时才会被调用。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentOutboxEvent event
               set event.status = com.example.codereview.agent.outbox.AgentOutboxStatus.SENT,
                   event.sentAt = :now,
                   event.claimedAt = null,
                   event.claimToken = null,
                   event.leaseExpiresAt = null,
                   event.lastError = null,
                   event.updatedAt = :now
             where event.id = :id
               and event.claimToken = :claimToken
               and event.status = com.example.codereview.agent.outbox.AgentOutboxStatus.PROCESSING
            """)
    int markSent(@Param("id") Long id, @Param("claimToken") String claimToken, @Param("now") Instant now);

    /** 遇到 nack、消息退回或确认超时后,把事件交回待发池。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentOutboxEvent event
               set event.status = com.example.codereview.agent.outbox.AgentOutboxStatus.PENDING,
                   event.attemptCount = event.attemptCount + 1,
                   event.nextAttemptAt = :nextAttemptAt,
                   event.claimedAt = null,
                   event.claimToken = null,
                   event.leaseExpiresAt = null,
                   event.lastError = :error,
                   event.updatedAt = :now
             where event.id = :id
               and event.claimToken = :claimToken
               and event.status = com.example.codereview.agent.outbox.AgentOutboxStatus.PROCESSING
            """)
    int markRetry(
            @Param("id") Long id,
            @Param("claimToken") String claimToken,
            @Param("now") Instant now,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("error") String error);

    /**
     * 回收持有者已经消失的事件。尝试次数会一并递增,好让「每次发布到一半就崩」的 worker
     * 最终把重试耗尽,而不是无限循环下去。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentOutboxEvent event
               set event.status = com.example.codereview.agent.outbox.AgentOutboxStatus.PENDING,
                   event.attemptCount = event.attemptCount + 1,
                   event.nextAttemptAt = :nextAttemptAt,
                   event.claimedAt = null,
                   event.claimToken = null,
                   event.leaseExpiresAt = null,
                   event.lastError = :error,
                   event.updatedAt = :now
             where event.status = com.example.codereview.agent.outbox.AgentOutboxStatus.PROCESSING
               and event.leaseExpiresAt is not null
               and event.leaseExpiresAt <= :now
            """)
    int requeueExpiredLeases(
            @Param("now") Instant now,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("error") String error);

    /**
     * 把重试次数烧光的事件挪进终态,免得一条路由键永远无效的事件每秒都来占用调度器。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentOutboxEvent event
               set event.status = com.example.codereview.agent.outbox.AgentOutboxStatus.FAILED,
                   event.failedAt = :now,
                   event.claimedAt = null,
                   event.claimToken = null,
                   event.leaseExpiresAt = null,
                   event.updatedAt = :now
             where event.status = com.example.codereview.agent.outbox.AgentOutboxStatus.PENDING
               and event.attemptCount >= :maxAttempts
            """)
    int failExhausted(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts);
}
