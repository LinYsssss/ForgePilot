package com.example.codereview.report;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewReportRepository extends JpaRepository<ReviewReport, Long> {

    List<ReviewReport> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /** Paginated variant for the API; the unbounded one stays for internal callers. */
    org.springframework.data.domain.Page<ReviewReport> findByProjectIdOrderByCreatedAtDesc(
            Long projectId, org.springframework.data.domain.Pageable pageable);

    Optional<ReviewReport> findByTaskId(Long taskId);

    Optional<ReviewReport> findByAgentRunId(Long agentRunId);

    void deleteByProjectId(Long projectId);

    void deleteByTaskId(Long taskId);
}
