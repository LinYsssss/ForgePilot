package com.example.codereview.agent.plan;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewPlanRepository extends JpaRepository<ReviewPlan, Long> {
    Optional<ReviewPlan> findByAgentRunId(Long agentRunId);
}
