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
            order by step.updatedAt, step.id
            """)
    List<Long> findStaleRunningIds(
            @Param("runningStatus") AgentStepStatus runningStatus,
            @Param("cutoff") Instant cutoff,
            @Param("recoverableStatuses") Collection<AgentRunStatus> recoverableStatuses,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentStep step
            set step.status = :interruptedStatus,
                step.errorMessage = :reason,
                step.finishedAt = :now,
                step.updatedAt = :now
            where step.id = :stepId
              and step.status = :runningStatus
              and step.updatedAt <= :cutoff
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
}
