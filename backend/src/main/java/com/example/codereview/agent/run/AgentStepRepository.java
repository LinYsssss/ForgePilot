package com.example.codereview.agent.run;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentStepRepository extends JpaRepository<AgentStep, Long> {
    List<AgentStep> findByAgentRunIdOrderBySequenceNo(Long agentRunId);

    Optional<AgentStep> findByAgentRunIdAndSequenceNo(Long agentRunId, int sequenceNo);
}
