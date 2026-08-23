package com.forgepilot.review;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 执行侧的那些语句：凡是<em>影响行数</em>本身即为判定结果、而非某个细节的，
 * 都在这里。它们是原生 SQL 且带条件，这是有意为之——
 * 「先读行、再写回」会把 WHERE 子句刚刚关上的那个窗口重新打开，
 * 而 Hibernate 的脏检查也报告不了「你匹配到了零行」这件事。
 *
 * <p>其中两条会读 {@code pull_request} 与 {@code requirement}。这不是越界：
 * design.md 2.1 裁定，凡是同时需要自己的表与 PR 的推导，都归 {@code review} 所有，
 * 因为依赖图只允许 {@code review -> scm}，反过来会在 ArchUnit 最重要的那条规则
 * 面前摆上一个环。任何地方都没有注入 {@code scm} 的仓库。
 *
 * <p>所有时间都来自 {@code now()}，而 PostgreSQL 会在事务开始时把它冻结。
 * 实测表明这个方向是安全的那一侧——偏旧的参照时间只会找出<em>更少</em>的
 * 过期租约，因此绝不会抢占一个还活着的 worker——而 design.md 6.6 否决了
 * {@code clock_timestamp()}，换来的是让这里每一条语句都待在自己的短事务里。
 */
public interface ReviewClaimRepository extends Repository<Review, Long> {

    /**
     * 该 PR <em>当前</em>的身份，也就是创建一次 Review 所依据的东西
     * （ARCHITECTURE.md 3.1）。修订取自需求的 {@code current_revision_id}，
     * 而不是取自 PR——后者只存了需求本身。
     */
    @Query(value = """
            SELECT p.project_id               AS "projectId",
                   p.head_sha                 AS "headSha",
                   p.review_input_fingerprint AS "reviewInputFingerprint",
                   p.requirement_id           AS "requirementId",
                   q.current_revision_id      AS "requirementRevisionId",
                   p.author_external_user_id  AS "authorExternalUserId"
              FROM pull_request p
              LEFT JOIN requirement q ON q.project_id = p.project_id AND q.id = p.requirement_id
             WHERE p.id = :pullRequestId
            """, nativeQuery = true)
    Optional<PullRequestIdentity> findPullRequestIdentity(@Param("pullRequestId") long pullRequestId);

    /**
     * 丢弃一个即将被放弃的 attempt 的产出。它必须与紧随其后的抢占处于同一个事务，
     * 因为 {@code fk_finding_review} 指向
     * {@code (project_id, id, execution_attempt)}：崩溃 worker 的 finding
     * 持有当前的 attempt 编号，在它们还存在时去递增该编号会以 23503 失败。
     * 实测表明这不是边角情况——它会把这个 Review 永久钉死，
     * 而唯一能解开它的 worker 恰恰就是那个已经死掉的。
     *
     * <p>{@code ON UPDATE CASCADE} 同样能让递增成功，但**绝不可**使用：
     * 实测它会静默地把已死 attempt 的 finding 重新标记为新 attempt 的产出，
     * 这比完全没有围栏更糟，因为它在**伪造证据**。
     *
     * <p>那个 EXISTS 子句，正是让本方法可以在还不知道抢占会不会成功之前
     * 就安全调用的原因。没有它，一个尝试抢占某个<em>活着的</em> RUNNING review
     * 却失败了的调用方，仍然已经把那个活 worker 的 finding 删掉了。
     * COMPLETED 被彻底排除在外：它的 finding 是历史。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM finding f
             WHERE f.project_id = :projectId
               AND f.review_id = :reviewId
               AND EXISTS (SELECT 1 FROM review r
                            WHERE r.project_id = f.project_id
                              AND r.id = f.review_id
                              AND r.execution_attempt = f.review_attempt
                              AND r.status <> 'COMPLETED'
                              AND (r.status <> 'RUNNING' OR r.lease_until < now()))
            """, nativeQuery = true)
    int discardAbandonedFindings(@Param("projectId") long projectId, @Param("reviewId") long reviewId);

    /**
     * 抢占（ARCHITECTURE.md 3.2）：一次原子的条件更新，只接纳 PENDING 的行
     * 或租约已过期的 RUNNING 行，并在**同一条语句**里铸造出那组围栏三元组。
     * 两个 worker 争抢一个过期租约时都会到达这一行；失败方会拿胜者已提交的版本
     * 重新求值 WHERE 子句，发现租约在未来，于是匹配到零行。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE review
               SET status = 'RUNNING',
                   execution_attempt = execution_attempt + 1,
                   execution_token = cast(:token AS uuid),
                   lease_until = now() + (:leaseSeconds * interval '1 second'),
                   updated_at = now()
             WHERE project_id = :projectId
               AND id = :reviewId
               AND (status = 'PENDING' OR (status = 'RUNNING' AND lease_until < now()))
            """, nativeQuery = true)
    int claim(@Param("projectId") long projectId, @Param("reviewId") long reviewId,
            @Param("token") String token, @Param("leaseSeconds") int leaseSeconds);

    /** 在抢占自己的行锁之下回读，因此不可能有人在此期间改动过它。 */
    @Query(value = "SELECT execution_attempt FROM review WHERE project_id = :projectId AND id = :reviewId",
            nativeQuery = true)
    Integer attemptOf(@Param("projectId") long projectId, @Param("reviewId") long reviewId);

    /**
     * 续租，是 ARCHITECTURE.md 3.2 要求必须匹配 token 的三次写入之一。
     * 它同时也是心跳：租约过期就是停滞信号，
     * 因此这里没有第二套存活检测机制，也没有第十七张表。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE review
               SET lease_until = now() + (:leaseSeconds * interval '1 second'),
                   updated_at = now()
             WHERE project_id = :projectId
               AND id = :reviewId
               AND execution_token = cast(:token AS uuid)
               AND status = 'RUNNING'
            """, nativeQuery = true)
    int renew(@Param("projectId") long projectId, @Param("reviewId") long reviewId,
            @Param("token") String token, @Param("leaseSeconds") int leaseSeconds);

    /**
     * 完成之后 token 仍留在行上。它是审计信息；而仅凭状态本身就已经为后续写入
     * 设好了围栏——对 worker 而言，除 RUNNING 之外的任何状态都不可写。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE review
               SET status = 'COMPLETED', lease_until = NULL, updated_at = now()
             WHERE project_id = :projectId
               AND id = :reviewId
               AND execution_token = cast(:token AS uuid)
               AND status = 'RUNNING'
            """, nativeQuery = true)
    int complete(@Param("projectId") long projectId, @Param("reviewId") long reviewId,
            @Param("token") String token);

    /**
     * 最常被遗忘的那道围栏。租约已过期的 worker 同样不得把这次 review 标记为 FAILED：
     * 实测表明，这里若不加围栏，一个已死的 attempt 就能把取代它的那个 attempt
     * 判为失败。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE review
               SET status = 'FAILED', lease_until = NULL, updated_at = now()
             WHERE project_id = :projectId
               AND id = :reviewId
               AND execution_token = cast(:token AS uuid)
               AND status = 'RUNNING'
            """, nativeQuery = true)
    int fail(@Param("projectId") long projectId, @Param("reviewId") long reviewId,
            @Param("token") String token);

    /**
     * 人工重试（ARCHITECTURE.md 3.2）：同一行回到 PENDING。attempt 由随后那次
     * 原子抢占铸造，与其他所有执行完全一致；在这里也递增一次，
     * 会让一次重试消耗掉两个 attempt 编号。它以 FAILED 为条件，
     * 因此两个人同时点重试会得到「一次重试 + 一个 409」，
     * 而不是两次排队执行。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE review
               SET status = 'PENDING',
                   execution_token = NULL,
                   lease_until = NULL,
                   updated_at = now()
             WHERE project_id = :projectId AND id = :reviewId AND status = 'FAILED'
            """, nativeQuery = true)
    int retryFailed(@Param("projectId") long projectId, @Param("reviewId") long reviewId);

    /**
     * reconciliation 的唯一查询，也是那条让「绝不补建」可查而非靠记的结构性规则：
     * <strong>FROM 子句里有且仅有 {@code review} 一张表。</strong>
     *
     * <p>任何由 {@code review} 驱动的结果集，都是**已经存在**的行的子集，
     * 因此无论条件写得多错，它都不可能产出一个从未被创建过的 Review。
     * 若改由 {@code pull_request} 驱动——哪怕带上
     * {@code NOT EXISTS (SELECT ... FROM review ...)}——结果集就变成了 PR 的子集，
     * 而对 PR 而言唯一可能的“恢复”就是**创建**。在同一份数据上实测：
     * PR 驱动的写法会返回一行「需要创建」，而这一条返回空。
     *
     * <p>这不是讲卫生。这里的补建会在有人编辑需求关联时触发，
     * 而 ARCHITECTURE.md 3.1 明确规定那种情况必须由人工重新发起审查——
     * 那将是一个绕过 {@code requestReview} 及其鉴权的自动触发器，
     * 花掉没人要求过的 token，并且是一条藏在单条 SQL 语句里的第二流水线。
     *
     * <p>{@code FAILED} **刻意**不在集合内：重试是人的行为（3.2），
     * 在这里把它捡起来，等于凭空多出一套没人要求过的重试策略。
     * {@code COMPLETED} 则根本不会被重跑。PENDING 是按 {@code updated_at}
     * 而不是 {@code created_at} 来衡量的，因为一个被重试过的行虽然“老”，
     * 但它是**刚刚**才变成 pending 的。
     */
    @Query(value = """
            SELECT r.project_id AS "projectId", r.id AS "reviewId"
              FROM review r
             WHERE (r.status = 'PENDING'
                        AND r.updated_at < now() - (:pendingStallSeconds * interval '1 second'))
                OR (r.status = 'RUNNING' AND r.lease_until < now())
             ORDER BY r.id
             LIMIT :limit
            """, nativeQuery = true)
    List<StalledReview> findStalled(@Param("pendingStallSeconds") int pendingStallSeconds,
            @Param("limit") int limit);

    /** 该 PR 当前的四元组输入，外加它是谁开的（D010）。 */
    interface PullRequestIdentity {

        Long getProjectId();

        String getHeadSha();

        String getReviewInputFingerprint();

        Long getRequirementId();

        Long getRequirementRevisionId();

        String getAuthorExternalUserId();
    }

    /** 一行已落库却没在运行的记录：要么从未被抢占，要么已被放弃。 */
    interface StalledReview {

        Long getProjectId();

        Long getReviewId();
    }
}
