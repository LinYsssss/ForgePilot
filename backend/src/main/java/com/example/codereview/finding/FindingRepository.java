package com.example.codereview.finding;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingRepository extends JpaRepository<Finding, Long> {
    List<Finding> findByAgentRunIdOrderByIdAsc(Long agentRunId);

    /** Paginated variant for the API; the unbounded one stays for internal callers. */
    org.springframework.data.domain.Page<Finding> findByAgentRunIdOrderByIdAsc(
            Long agentRunId, org.springframework.data.domain.Pageable pageable);
}
