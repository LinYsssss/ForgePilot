package com.forgepilot.review;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 支撑 Finding 血缘的那些读取（ARCHITECTURE.md 3.6）。它们无一例外都限定在
 * **单个** PR 之内，因为连续性只在一个 PR 内部计算：另一个 PR 里相同的
 * {@code finding_key} 描述的是另一处改动，绝不能把驳回带过去。
 *
 * <p>继承的是朴素的 {@code Repository} 标记接口而非 {@code JpaRepository}：
 * 血缘是读操作，这里不应该有任何东西能写 Finding。
 */
public interface FindingLineageRepository extends Repository<Finding, Long> {

    /**
     * 本 PR 紧邻的上一次 COMPLETED Review，按 3.6.3 要求以
     * {@code (created_at, id)} 定序——否则同一个时钟刻度内创建的两个 Review
     * 会按执行计划碰巧返回的顺序比较，「上一轮」也就不再是一个事实。
     *
     * <p>用原生 SQL，是因为 PostgreSQL 的行值比较能用一个表达式表达
     * “严格早于这次 Review”；JPQL 没有这种比较，只能把 created_at 子查询写两遍。
     */
    @Query(value = """
            SELECT prev.id FROM review prev
             WHERE prev.project_id = :projectId
               AND prev.pull_request_id = :pullRequestId
               AND prev.status = 'COMPLETED'
               AND (prev.created_at, prev.id)
                   < (SELECT self.created_at, self.id FROM review self WHERE self.id = :reviewId)
             ORDER BY prev.created_at DESC, prev.id DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<Long> findPreviousCompletedReviewId(@Param("projectId") long projectId,
            @Param("pullRequestId") long pullRequestId, @Param("reviewId") long reviewId);

    @Query("SELECT f FROM Finding f WHERE f.projectId = :projectId AND f.reviewId = :reviewId ORDER BY f.id ASC")
    List<Finding> findFindingsOfReview(@Param("projectId") long projectId, @Param("reviewId") long reviewId);

    /**
     * 在本 PR 的整个历史中，针对这个 {@code finding_key} 的**最近一次人工判断**
     * 所对应的那条 Finding——但仅当该判断是一次驳回时才返回（3.6.4）。
     *
     * <p>「是否为驳回」这个判定是在排序<em>之后</em>施加的，而不是塞进排序里。
     * 先按 {@code to_status = 'REJECTED'} 过滤，找到的会是「最近一次驳回」
     * 而不是「最近一次判断」，于是一条被驳回、之后又被重开的 finding
     * 会在下一轮被重新抑制，重开操作等于悄悄地自我撤销了。
     *
     * <p>定序用的是 3.6.4 指定的
     * {@code (finding_event.created_at, finding_event.id)}；
     * 而与 {@code review} 的连接，正是把「在另一个 PR 上作出的判断」
     * 挡在本次结果之外的那道门。
     */
    @Query(value = """
            SELECT latest.finding_id FROM (
                SELECT e.finding_id, e.to_status
                  FROM finding_event e
                  JOIN finding f ON f.project_id = e.project_id AND f.id = e.finding_id
                  JOIN review r ON r.project_id = f.project_id AND r.id = f.review_id
                 WHERE e.project_id = :projectId
                   AND r.pull_request_id = :pullRequestId
                   AND f.finding_key = :findingKey
                 ORDER BY e.created_at DESC, e.id DESC
                 LIMIT 1) AS latest
             WHERE latest.to_status = 'REJECTED'
            """, nativeQuery = true)
    Optional<Long> findMostRecentlyRejectedFindingId(@Param("projectId") long projectId,
            @Param("pullRequestId") long pullRequestId, @Param("findingKey") String findingKey);

    @Query("SELECT f FROM Finding f WHERE f.projectId = :projectId AND f.id = :findingId")
    Optional<Finding> findFinding(@Param("projectId") long projectId, @Param("findingId") long findingId);
}
