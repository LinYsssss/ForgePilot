package com.forgepilot.requirement;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * 每次读取都带 {@code projectId}；不存在那种“事后再补一道项目检查”的
 * 裸 id 查询（ARCHITECTURE.md 2.3）。
 *
 * <p>抓取图里带上了当前修订，因为每个调用方都需要它的标题与 seq：
 * {@code requirement} 行本身不携带任何文本。
 */
public interface RequirementRepository extends JpaRepository<Requirement, Long> {

    @EntityGraph(attributePaths = "currentRevision")
    Optional<Requirement> findByProjectIdAndId(long projectId, long id);

    @EntityGraph(attributePaths = "currentRevision")
    List<Requirement> findByProjectIdOrderByIdAsc(long projectId);

    /**
     * 软删之后的取值口。产品面的每一条读取与写入都走带 {@code AndDeletedAtIsNull}
     * 的这两个方法，只有删除本身用上面未过滤的版本——它必须能判定「已经删了」。
     */
    @EntityGraph(attributePaths = "currentRevision")
    Optional<Requirement> findByProjectIdAndIdAndDeletedAtIsNull(long projectId, long id);

    @EntityGraph(attributePaths = "currentRevision")
    List<Requirement> findByProjectIdAndDeletedAtIsNullOrderByIdAsc(long projectId);

    /**
     * 成员离开项目时释放它的需求指派。批量更新绕过持久化上下文，这在移除事务里是
     * 安全的：此后没有人再读需求实体。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Requirement r set r.assigneeId = null "
            + "where r.projectId = :projectId and r.assigneeId = :userId")
    int clearAssignee(long projectId, long userId);
}
