package com.example.codereview.mq;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MqTaskLogRepository extends JpaRepository<MqTaskLog, Long> {

    List<MqTaskLog> findByTaskIdOrderByCreatedAtDesc(Long taskId);

    /** Paginated variant used by the API; the unbounded one stays for internal callers. */
    Page<MqTaskLog> findByTaskIdOrderByCreatedAtDesc(Long taskId, Pageable pageable);

    void deleteByTaskId(Long taskId);

    void deleteByTaskIdIn(List<Long> taskIds);
}
