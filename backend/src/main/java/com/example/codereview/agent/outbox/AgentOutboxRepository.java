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
 * Every state transition here is a conditional bulk update rather than a load-mutate-save, so the
 * database itself decides who wins. Publishing happens outside a transaction and can take seconds;
 * by the time a worker writes its result back, its lease may already have been reaped and the event
 * handed to somebody else. Gating each write on {@code (id, claim_token, status)} makes that case
 * return 0 rows updated instead of silently clobbering the newer attempt.
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
     * Takes ownership of a pending event. Returns 1 for the winner and 0 for everybody else, so
     * concurrent schedulers can race safely.
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

    /** Only ever called once the broker has acknowledged the message and not returned it. */
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

    /** Hands the event back to the pending pool after a nack, return or confirm timeout. */
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
     * Reclaims events whose holder went away. The attempt counter is incremented so a worker that
     * keeps crashing mid-publish eventually exhausts its retries instead of looping forever.
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
     * Moves events that have burned through their retries into the terminal state, so an event with
     * a permanently unroutable key stops occupying the scheduler every second.
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
