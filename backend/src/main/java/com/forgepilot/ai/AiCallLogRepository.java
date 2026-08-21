package com.forgepilot.ai;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Every read carries {@code projectId}: rows are never looked up by bare id (ARCHITECTURE.md 2.3). */
public interface AiCallLogRepository extends JpaRepository<AiCallLog, Long> {

    List<AiCallLog> findByProjectIdOrderByIdAsc(long projectId);
}
