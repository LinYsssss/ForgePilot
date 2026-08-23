package com.forgepilot.review;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 支撑两条人工闭环的那些语句：一次性的 Review Decision，以及 Finding 生命周期。
 * 这里的每一次写入都是<em>条件</em>更新，其影响行数就是答案——
 * 为 1 表示调用方完成了这次变动，其余任何值都表示当时的状态并非调用方
 * 被授权时所针对的那个，于是该请求应答 409。
 *
 * <p>实测表明，「先读、再无条件写」会在两个彼此独立的方向上出错，
 * 而本文件的存在正是为了避开它们。它会让两次并发决策**都**成功；
 * 它还会让审计轨迹记下一个早已过期的 {@code from_status}——
 * 两个都从 {@code OPEN} 出发的事件，描述出一段从未发生过的历史
 * （research/fencing-and-concurrency-measured.md 5.1、7.5）。
 *
 * <p>有几条语句点到了 {@code pull_request}、{@code requirement} 与
 * {@code acceptance_criterion}。{@code review} 是那个唯一的跨模块编排者
 * （ARCHITECTURE.md 1.3），它在自己的 SQL 里触达那些表，
 * 而不是去注入另一个功能模块的 Repository——后者既被 ArchUnit 规则 4 禁止，
 * 也会让 3.1 的六项前置条件无法在同一把行锁之下求值。
 * 在 {@code scm} 上开一个查询 facade 会是那三次 PR 读取更整洁的归宿，
 * 值得在下次打开那个模块时补上。
 */
interface DecisionRepository extends Repository<Review, Long> {

    /**
     * 只读那个不可变的父级 id，使调用方能够<em>先</em>取得 PR 行锁，
     * 再去加载 Review 本身。
     *
     * <p>这个顺序是承重的。在 READ COMMITTED 之下，在加锁之前发出的读取
     * 看到的是并发竞争胜者提交之前的快照，于是那些前置条件会针对一个
     * 已经不存在的状态被检查（research 5.1）。这里**刻意**不加载实体：
     * 第二次 {@code findByProjectIdAndId} 会由持久化上下文直接作答，
     * 那根本不是一次新鲜读取。
     */
    @Query(value = """
            SELECT r.pull_request_id FROM review r
             WHERE r.project_id = :projectId AND r.id = :reviewId
            """, nativeQuery = true)
    Optional<Long> pullRequestIdOf(@Param("projectId") long projectId, @Param("reviewId") long reviewId);

    /**
     * 取得 ARCHITECTURE.md 3.1 所要求的 PR 行锁，并返回它锁住时的那个 head。
     *
     * <p>这把锁不是围着条件更新买的保险，它是让前置条件 3、4、5 在并发下
     * **具有意义**的唯一依靠。实测：没有它时，更新的 EvalPlanQual 只会重新检查
     * 它自己的目标行，于是被连接进来的 {@code pull_request} 仍然是一次普通的
     * 快照读——结果是给 {@code head1} 发出了 {@code APPROVE}，
     * 而 SCM 早已把这个 PR 推进到了 {@code head2}（research 5.5）。
     * SCM 自己的写入方在把 head 往前滚之前也会取同一把行锁，因此二者得以串行化。
     */
    @Query(value = """
            SELECT p.head_sha FROM pull_request p
             WHERE p.project_id = :projectId AND p.id = :pullRequestId
             FOR UPDATE
            """, nativeQuery = true)
    Optional<String> lockPullRequestAndReadHead(@Param("projectId") long projectId,
            @Param("pullRequestId") long pullRequestId);

    /** 这把锁在读取路径上的对应物：推导 {@code isCurrent} 时不得锁任何东西。 */
    @Query(value = """
            SELECT p.head_sha FROM pull_request p
             WHERE p.project_id = :projectId AND p.id = :pullRequestId
            """, nativeQuery = true)
    Optional<String> currentHeadSha(@Param("projectId") long projectId,
            @Param("pullRequestId") long pullRequestId);

    @Query(value = """
            SELECT p.review_input_fingerprint FROM pull_request p
             WHERE p.project_id = :projectId AND p.id = :pullRequestId
            """, nativeQuery = true)
    Optional<String> currentReviewInputFingerprint(@Param("projectId") long projectId,
            @Param("pullRequestId") long pullRequestId);

    /**
     * PR 的展示编号。为审查列表按**项目**读一次，而不是按行读一次：
     * 这份列表是跨 PR 的，否则就会变成每条 review 一次查询。
     */
    @Query(value = """
            SELECT p.id, p.external_number FROM pull_request p WHERE p.project_id = :projectId
            """, nativeQuery = true)
    List<Object[]> pullRequestNumbers(@Param("projectId") long projectId);

    /**
     * 沿着 {@code pull_request.requirement_id -> requirement.current_revision_id}
     * 找到该 PR 当前指向的那个需求修订。
     *
     * <p>返回空表示「没有修订」，而抵达这一结果的两条路径——完全没有关联，
     * 或者关联的需求还没有发布过任何修订——给出的是同一个答案。
     * 调用方此前已经确认过该 PR 存在，因此「空」绝不会与「行不存在」相混淆。
     */
    @Query(value = """
            SELECT req.current_revision_id FROM pull_request p
              JOIN requirement req ON req.project_id = p.project_id AND req.id = p.requirement_id
             WHERE p.project_id = :projectId AND p.id = :pullRequestId
            """, nativeQuery = true)
    Optional<Long> currentRequirementRevisionId(@Param("projectId") long projectId,
            @Param("pullRequestId") long pullRequestId);

    /**
     * ARCHITECTURE.md 3.1 的决策闸门，六项前置条件全部折进 {@code WHERE} 子句，
     * 使影响行数本身即为裁定结果。
     *
     * <p>服务层还会逐条检查这六项，因为调用方理应被告知是哪一条拒绝了它。
     * 那次预检**不是**闸门：它跑在本语句之前，两者之间它可能就已经过期了。
     * 这里才是闸门。
     *
     * <p>前置条件 5 写作 {@code IS NOT DISTINCT FROM} 而不是 {@code =}。
     * 用 {@code =} 时两个 NULL 得到 NULL，该行永远匹配不上，
     * 于是一个不实现任何需求的 PR 将永远无法被决策——这是实测出来的，
     * 且它表现为「按钮点了没反应」，而不是一个错误（research 5.6）。
     *
     * <p>前置条件 6 每次都从数据行中推导，对照该 PR 的<em>当前</em> head。
     * 它从不缓存，也绝不作为标志位存在 {@code pull_request} 上：
     * 实测表明，force-push 回退到一个被封锁的 head 时，这种写法会正确地重新上锁，
     * 而存标志位的写法不会；改成读「最近一次决策」或「有没有 APPROVE」，
     * 则会在一行代码都不改的情况下把封锁解除掉（research 6.2、6.3）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE review r
               SET decision = :decision,
                   decision_by = :actorId,
                   decision_at = now(),
                   decision_comment = CAST(:comment AS TEXT),
                   updated_at = now()
              FROM pull_request p
             WHERE r.project_id = :projectId
               AND r.id = :reviewId
               AND p.project_id = r.project_id
               AND p.id = r.pull_request_id
               AND r.status = 'COMPLETED'
               AND r.decision = 'PENDING'
               AND r.head_sha = p.head_sha
               AND r.review_input_fingerprint = p.review_input_fingerprint
               AND r.requirement_revision_id IS NOT DISTINCT FROM
                   (SELECT req.current_revision_id FROM requirement req
                     WHERE req.project_id = p.project_id AND req.id = p.requirement_id)
               AND NOT EXISTS (
                   SELECT 1 FROM review blocking
                    WHERE blocking.project_id = r.project_id
                      AND blocking.pull_request_id = r.pull_request_id
                      AND blocking.head_sha = p.head_sha
                      AND blocking.decision = 'REQUEST_CHANGES')
            """, nativeQuery = true)
    int decideIfStillPending(@Param("projectId") long projectId, @Param("reviewId") long reviewId,
            @Param("decision") String decision, @Param("actorId") long actorId,
            @Param("comment") String comment);

    /**
     * 除认领与重开之外的每一次 Finding 变动——那两者分别多带一个列和一个条件。
     *
     * <p>{@code status = :fromStatus} 正是让审计行保持诚实的那一笔：
     * 随后写入的 {@code from_status} 就是这个参数，
     * 而它是被**数据库匹配到**的，不是片刻之前读来的。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE finding SET status = :toStatus, updated_at = now()
             WHERE project_id = :projectId AND id = :findingId AND status = :fromStatus
            """, nativeQuery = true)
    int moveFinding(@Param("projectId") long projectId, @Param("findingId") long findingId,
            @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus);

    /**
     * 认领就是把自己指派上去：PRD.md 3 授予 DEVELOPER “Finding 认领”，
     * 却没有授予任何人“指派给别人”，因此指派就发生在这里，
     * 也不存在另一个可能授予更多权限的独立端点（design.md 3.3）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE finding SET status = 'IN_PROGRESS', assignee_id = :actorId, updated_at = now()
             WHERE project_id = :projectId AND id = :findingId AND status = 'CONFIRMED'
            """, nativeQuery = true)
    int claimFinding(@Param("projectId") long projectId, @Param("findingId") long findingId,
            @Param("actorId") long actorId);

    /**
     * 重开，由 {@code WHERE} 子句本身把它限制在「被继承的抑制项」上。
     * PRD.md 5 <strong>只</strong>在 {@code continuity = SUPPRESSED} 时允许它；
     * 普通的驳回对任何角色都是不可逆的。
     *
     * <p>CHECK 表达不了这一点——那需要一个子查询——而 design.md 6.8 拒绝再加
     * 第二个约束触发器，因为 ARCHITECTURE.md 2.1 是逐个授权约束触发器的，
     * 而不是按类别授权。于是这条规则住在这里，而且是住在**条件**里，
     * 而不是住在一次会留下窗口的前置读取里。
     *
     * <p>{@code continuity} 不被触碰。重开不会抹掉这条 finding 从哪来
     * （PRD.md 5：血缘是关于历史的事实）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE finding SET status = 'OPEN', updated_at = now()
             WHERE project_id = :projectId AND id = :findingId
               AND status = 'REJECTED' AND continuity = 'SUPPRESSED'
            """, nativeQuery = true)
    int reopenSuppressedFinding(@Param("projectId") long projectId, @Param("findingId") long findingId);

    /**
     * 一次 review 的 finding 所引用的那些验收条件对应的稳定 {@code ac_key}。
     * 按 id 读取并按项目过滤，因此来自别处的 id 就是查不到 key。
     */
    @Query(value = """
            SELECT ac.id, ac.ac_key FROM acceptance_criterion ac
             WHERE ac.project_id = :projectId AND ac.id IN (:acIds)
            """, nativeQuery = true)
    List<Object[]> acceptanceCriterionKeys(@Param("projectId") long projectId,
            @Param("acIds") Collection<Long> acIds);
}
