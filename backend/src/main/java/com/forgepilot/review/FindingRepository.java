package com.forgepilot.review;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface FindingRepository extends JpaRepository<Finding, Long> {

    Optional<Finding> findByProjectIdAndId(long projectId, long id);

    List<Finding> findByProjectIdAndReviewIdOrderByIdAsc(long projectId, long reviewId);

    /**
     * 成员离开项目时释放它认领的 Finding。只动 assignee，一条
     * {@code finding_event} 都不碰：认领是活权限，已发生的人工决定是事实。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Finding f set f.assigneeId = null "
            + "where f.projectId = :projectId and f.assigneeId = :userId")
    int clearAssignee(long projectId, long userId);
}
