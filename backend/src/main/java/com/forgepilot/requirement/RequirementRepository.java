package com.forgepilot.requirement;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 每次读取都带 {@code projectId}；不存在那种“事后再补一道项目检查”的
 * 裸 id 查询（ARCHITECTURE.md 2.3）。
 *
 * <p>抓取图里带上了当前修订，因为每个调用方都需要它的标题与 seq：
 * {@code requirement} 行本身不携带任何文本（D011）。
 */
public interface RequirementRepository extends JpaRepository<Requirement, Long> {

    @EntityGraph(attributePaths = "currentRevision")
    Optional<Requirement> findByProjectIdAndId(long projectId, long id);

    @EntityGraph(attributePaths = "currentRevision")
    List<Requirement> findByProjectIdOrderByIdAsc(long projectId);
}
