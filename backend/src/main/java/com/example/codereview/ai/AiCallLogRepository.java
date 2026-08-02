package com.example.codereview.ai;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiCallLogRepository extends JpaRepository<AiCallLog, Long> {

    List<AiCallLog> findByProjectIdOrderByCreatedAtDesc(Long projectId, Pageable pageable);

    List<AiCallLog> findByTaskIdOrderByCreatedAtDesc(Long taskId, Pageable pageable);

    List<AiCallLog> findByProjectIdAndTaskIdOrderByCreatedAtDesc(Long projectId, Long taskId, Pageable pageable);

    /* Page 变体("Page"位于 find 与 By 之间,不参与条件解析):分页信封需要总数。 */

    org.springframework.data.domain.Page<AiCallLog> findPageByProjectIdOrderByCreatedAtDesc(Long projectId, Pageable pageable);

    org.springframework.data.domain.Page<AiCallLog> findPageByTaskIdOrderByCreatedAtDesc(Long taskId, Pageable pageable);

    org.springframework.data.domain.Page<AiCallLog> findPageByProjectIdAndTaskIdOrderByCreatedAtDesc(Long projectId, Long taskId, Pageable pageable);

    void deleteByProjectId(Long projectId);

    void deleteByTaskId(Long taskId);
}
