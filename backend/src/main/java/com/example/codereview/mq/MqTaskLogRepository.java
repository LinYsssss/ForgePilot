package com.example.codereview.mq;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MqTaskLogRepository extends JpaRepository<MqTaskLog, Long> {

    List<MqTaskLog> findByTaskIdOrderByCreatedAtDesc(Long taskId);
}
