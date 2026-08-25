package com.forgepilot.project;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** 每次读取都带 {@code projectId}；台账同样不存在跨项目的裸 id 查询。 */
interface ProjectDeletionRecordRepository extends JpaRepository<ProjectDeletionRecord, Long> {

    List<ProjectDeletionRecord> findByProjectIdOrderByIdAsc(long projectId);

    List<ProjectDeletionRecord> findByProjectIdAndResourceTypeOrderByIdAsc(
            long projectId, DeletedResourceType resourceType);
}
