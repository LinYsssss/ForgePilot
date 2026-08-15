package com.example.codereview.finding;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingRepository extends JpaRepository<Finding, Long> {
    List<Finding> findByAgentRunIdOrderByIdAsc(Long agentRunId);

    /** 给 API 用的分页版本;无界的那个留给内部调用方。 */
    org.springframework.data.domain.Page<Finding> findByAgentRunIdOrderByIdAsc(
            Long agentRunId, org.springframework.data.domain.Pageable pageable);
}
