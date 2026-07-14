package com.example.codereview.agent.run;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
}