package com.forgepilot.ai;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** 每次读取都带上 {@code projectId}：绝不允许仅凭裸 id 查行（ARCHITECTURE.md 2.3）。 */
public interface AiCallLogRepository extends JpaRepository<AiCallLog, Long> {

    List<AiCallLog> findByProjectIdOrderByIdAsc(long projectId);
}
