package com.example.codereview.agent.run;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AgentStepRepository extends JpaRepository<AgentStep, Long> {
    List<AgentStep> findByAgentRunIdOrderBySequenceNo(Long agentRunId);

    Optional<AgentStep> findByAgentRunIdAndSequenceNo(Long agentRunId, int sequenceNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select step
            from AgentStep step
            where step.agentRunId = :agentRunId
              and step.sequenceNo = :sequenceNo
            """)
    Optional<AgentStep> findForUpdate(
            @Param("agentRunId") Long agentRunId,
            @Param("sequenceNo") int sequenceNo
    );

    @Query("""
            select step.id
            from AgentStep step, AgentRun run
            where run.id = step.agentRunId
              and step.status = :runningStatus
              and step.updatedAt <= :cutoff
              and run.updatedAt <= :cutoff
              and run.status in :recoverableStatuses
              and (step.leaseExpiresAt is null or step.leaseExpiresAt <= :now)
            order by step.updatedAt, step.id
            """)
    List<Long> findStaleRunningIds(
            @Param("runningStatus") AgentStepStatus runningStatus,
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            @Param("recoverableStatuses") Collection<AgentRunStatus> recoverableStatuses,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentStep step
            set step.status = :interruptedStatus,
                step.errorMessage = :reason,
                step.finishedAt = :now,
                step.updatedAt = :now,
                step.executionToken = null,
                step.workerId = null,
                step.leaseExpiresAt = null
            where step.id = :stepId
              and step.status = :runningStatus
              and step.updatedAt <= :cutoff
              and (step.leaseExpiresAt is null or step.leaseExpiresAt <= :now)
              and exists (
                  select run.id
                  from AgentRun run
                  where run.id = step.agentRunId
                    and run.updatedAt <= :cutoff
                    and run.status in :recoverableStatuses
              )
            """)
    int interruptIfStale(
            @Param("stepId") Long stepId,
            @Param("runningStatus") AgentStepStatus runningStatus,
            @Param("interruptedStatus") AgentStepStatus interruptedStatus,
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            @Param("reason") String reason,
            @Param("recoverableStatuses") Collection<AgentRunStatus> recoverableStatuses
    );

    /**
     * Pushes the lease out while a step is still executing. Conditional on the token so a worker
     * that already lost the step cannot resurrect its claim, and on RUNNING so a completed step is
     * never re-leased.
     *
     * @return 1 while this worker still holds the step, 0 once it does not.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentStep step
               set step.leaseExpiresAt = :expiry,
                   step.updatedAt = :now
             where step.id = :stepId
               and step.executionToken = :executionToken
               and step.status = com.example.codereview.agent.run.AgentStepStatus.RUNNING
            """)
    int renewLease(
            @Param("stepId") Long stepId,
            @Param("executionToken") String executionToken,
            @Param("expiry") Instant expiry,
            @Param("now") Instant now
    );
}
