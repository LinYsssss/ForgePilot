package com.example.codereview.agent.orchestration;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentScmContextRepository extends JpaRepository<AgentScmContext, Long> {
    Optional<AgentScmContext> findByAgentRunId(Long agentRunId);
}
