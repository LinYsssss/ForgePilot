package com.example.codereview.report;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewReportRepository extends JpaRepository<ReviewReport, Long> {

    List<ReviewReport> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    Optional<ReviewReport> findByTaskId(Long taskId);

    void deleteByProjectId(Long projectId);
}
