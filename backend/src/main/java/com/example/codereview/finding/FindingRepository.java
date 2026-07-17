package com.example.codereview.finding;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingRepository extends JpaRepository<Finding, Long> {
    List<Finding> findByAgentRunIdOrderByIdAsc(Long agentRunId);
}
