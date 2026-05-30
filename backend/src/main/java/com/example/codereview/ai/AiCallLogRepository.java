package com.example.codereview.ai;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiCallLogRepository extends JpaRepository<AiCallLog, Long> {

    List<AiCallLog> findByProjectIdOrderByCreatedAtDesc(Long projectId, Pageable pageable);

    List<AiCallLog> findByTaskIdOrderByCreatedAtDesc(Long taskId, Pageable pageable);

    List<AiCallLog> findByProjectIdAndTaskIdOrderByCreatedAtDesc(Long projectId, Long taskId, Pageable pageable);
}
