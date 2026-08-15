package com.example.codereview.report;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewReportRepository extends JpaRepository<ReviewReport, Long> {

    List<ReviewReport> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /** 给 API 用的分页版本;无界的那个留给内部调用方。 */
    org.springframework.data.domain.Page<ReviewReport> findByProjectIdOrderByCreatedAtDesc(
            Long projectId, org.springframework.data.domain.Pageable pageable);

    Optional<ReviewReport> findByTaskId(Long taskId);

    Optional<ReviewReport> findByAgentRunId(Long agentRunId);

    void deleteByProjectId(Long projectId);

    void deleteByTaskId(Long taskId);
}
