package com.example.codereview.mq;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MqTaskLogRepository extends JpaRepository<MqTaskLog, Long> {

    List<MqTaskLog> findByTaskIdOrderByCreatedAtDesc(Long taskId);

    /** 给 API 用的分页版本;无界的那个留给内部调用方。 */
    Page<MqTaskLog> findByTaskIdOrderByCreatedAtDesc(Long taskId, Pageable pageable);

    void deleteByTaskId(Long taskId);

    void deleteByTaskIdIn(List<Long> taskIds);
}
