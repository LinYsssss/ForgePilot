package com.example.codereview.agent.outbox;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AgentOutboxRepository extends JpaRepository<AgentOutboxEvent, Long> {

    @Query("""
            select event.id
            from AgentOutboxEvent event
            where event.status = com.example.codereview.agent.outbox.AgentOutboxStatus.PENDING
              and event.nextAttemptAt <= :now
            order by event.createdAt, event.id
            """)
    List<Long> findAvailableIds(@Param("now") Instant now, Pageable pageable);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentOutboxEvent event
               set event.status = com.example.codereview.agent.outbox.AgentOutboxStatus.PROCESSING,
                   event.claimedAt = :now,
                   event.updatedAt = :now
             where event.id = :id
               and event.status = com.example.codereview.agent.outbox.AgentOutboxStatus.PENDING
               and event.nextAttemptAt <= :now
            """)
    int claim(@Param("id") Long id, @Param("now") Instant now);
}
