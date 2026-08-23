package com.forgepilot.review;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 每次读取都限定在项目内，因此猜到的、属于别的项目的 id，
 * 与一个从未存在过的 id 答得一模一样。
 *
 * <p>抢占与完成的语句是原生 SQL 且带条件，这是有意为之。它们的**影响行数
 * 本身就是判定结果**：一个租约已过期的 worker 会匹配到零行，
 * 因此它既无法完成、无法置失败，也无法续租。先读行再写入的写法，
 * 会把这些条件刚刚关上的那个窗口重新打开。
 */
public interface ReviewRepository extends JpaRepository<Review, Long> {

    Optional<Review> findByProjectIdAndId(long projectId, long id);

    List<Review> findByProjectIdAndPullRequestIdOrderByCreatedAtAscIdAsc(long projectId, long pullRequestId);

    /** 整个项目的 Review，最新的在前：代码审查页面列出的就是它。 */
    List<Review> findByProjectIdOrderByCreatedAtDescIdDesc(long projectId);

    /**
     * 支撑“幂等地创建或接管”的身份查找（ARCHITECTURE.md 3.1）。
     * 用 {@code IS NOT DISTINCT FROM} 而不是 {@code =}：没有关联需求的 PR
     * 两侧的修订都是 null，而 {@code =} 求值为 unknown、永远匹配不上，
     * 于是每一次投递都会去尝试插入一条重复记录。
     */
    @Query(value = """
            SELECT * FROM review
             WHERE pull_request_id = :pullRequestId
               AND head_sha = :headSha
               AND review_input_fingerprint = :fingerprint
               AND requirement_revision_id IS NOT DISTINCT FROM :revisionId
            """, nativeQuery = true)
    Optional<Review> findByIdentity(@Param("pullRequestId") long pullRequestId,
            @Param("headSha") String headSha, @Param("fingerprint") String fingerprint,
            @Param("revisionId") Long revisionId);

    /**
     * 决策闸门（ARCHITECTURE.md 3.1）：这个 head 上是否已经带了 REQUEST_CHANGES？
     * 每次都现算，而不是缓存在 PR 行上——force-push 回退到某个较旧的 head 时
     * 必须自动重新上锁，而一个存下来的标志位做不到这一点。
     */
    boolean existsByProjectIdAndPullRequestIdAndHeadShaAndDecision(
            long projectId, long pullRequestId, String headSha, ReviewDecision decision);
}
