package com.example.codereview.agent.orchestration;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentAnalysisContextRepository extends JpaRepository<AgentAnalysisContext, Long> {
    Optional<AgentAnalysisContext> findByAgentRunId(Long agentRunId);
}
