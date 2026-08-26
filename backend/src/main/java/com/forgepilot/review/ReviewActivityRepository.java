package com.forgepilot.review;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 支撑审查活动状态的那**一次**读取：把项目内每一个 PR 与它<em>当前有效</em>的
 * 那次 Review 配对；若没有，则配上一组 null。
 *
 * <p>它用朴素 SQL 而不是 JPA，有两个都很要紧的理由。
 *
 * <p>其一，「当前是否有效」必须由数据库来判定。ARCHITECTURE.md 3.1 把
 * 需求修订的比较钉死为 {@code IS NOT DISTINCT FROM}，即“NULL 亦须相等”。
 * 若写成 {@code =}，没有关联需求的 PR 会拿 null 与 null 比较，谓词结果为 unknown，
 * 连接因此丢掉这一行，于是该 PR 会永远报告 {@code REVIEW_REQUIRED}——
 * 再多的审查、再多的手动触发都清不掉它。Java 侧的等价写法同样会出错：
 * {@code a.equals(b)} 在 {@code a} 为 null 时抛异常，
 * 而 {@code a == b} 对两个超出缓存范围的装箱 {@code Long} 为 false。
 * 放在数据库里比较，三个坑一并避开。
 *
 * <p>其二，活动状态横跨 {@code pull_request}（归 {@code scm} 所有）与
 * {@code review}。ARCHITECTURE.md 1.1 的箭头方向是 {@code review -> scm}，
 * 而 {@code review} 不得注入另一个功能模块的仓库，
 * 因此这次连接在这里用 SQL 表达——在这里它不会产生任何类型依赖。
 *
 * <p>是**一条**语句，而不是每个 PR 一条：需求列表页一次要的是整个项目。
 */
@Repository
class ReviewActivityRepository {

    /**
     * 只写一次、由两个调用方共用，使「当前是否有效」的判定不会在二者之间漂移。
     * 这两个连接性质不同：{@code requirement} 提供的是该 PR <em>当前的</em>修订
     * （{@code pull_request} 上并没有这样一个列），
     * 而 {@code review} 才是身份匹配。
     *
     * <p>{@code review.requirement_id} **刻意**不参与匹配：
     * 一个修订恰好属于一条需求，且三列外键强制了这一点，
     * 因此匹配上修订本身就已经蕴含了匹配上需求。
     */
    private static final String CURRENT_REVIEW_PER_PULL_REQUEST = """
            select pr.requirement_id as requirement_id,
                   cur.status        as review_status,
                   cur.decision      as review_decision
              from pull_request pr
              left join requirement r
                     on r.project_id = pr.project_id
                    and r.id = pr.requirement_id
              left join review cur
                     on cur.project_id = pr.project_id
                    and cur.pull_request_id = pr.id
                    and cur.head_sha = pr.head_sha
                    and cur.review_input_fingerprint = pr.review_input_fingerprint
                    and cur.requirement_revision_id is not distinct from r.current_revision_id
             where pr.project_id = ?
            """;

    /**
     * {@code review_status} 与 {@code review_decision} 都是 NOT NULL 列，
     * 因此当且仅当左连接没有找到当前有效的 Review 时，它们才会同时为 null。
     */
    private static final RowMapper<CurrentReview> ROW = (rs, index) -> {
        String status = rs.getString("review_status");
        return new CurrentReview(rs.getObject("requirement_id", Long.class),
                status == null ? null : ReviewStatus.valueOf(status),
                status == null ? null : ReviewDecision.valueOf(rs.getString("review_decision")));
    };

    private final JdbcTemplate jdbc;

    ReviewActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 项目内的每一个 PR，包括那些没有关联需求的。这里不把它们过滤掉，
     * 因为它们并不是缺陷：P1 允许一个 PR 完全没有关联，
     * 它依然有属于自己的活动状态，只是不进入任何需求的聚合。
     * 这两个事实中哪一个适用，是调用方的判断，而不是本查询的判断。
     */
    List<CurrentReview> ofProject(long projectId) {
        return jdbc.query(CURRENT_REVIEW_PER_PULL_REQUEST + "order by pr.id", ROW, projectId);
    }

    List<CurrentReview> ofRequirement(long projectId, long requirementId) {
        return jdbc.query(CURRENT_REVIEW_PER_PULL_REQUEST + "and pr.requirement_id = ? order by pr.id",
                ROW, projectId, requirementId);
    }

    /**
     * 之所以需要它，是因为一条一个 PR 都没有的需求仍然有它的活动状态
     * ——{@code NO_PR}——而任何基于 {@code pull_request} 的连接都不可能
     * 为它产生出一行来。
     *
     * <p>软删的需求被排除：它已经离开产品面，不该继续出现在活动概览里。
     * 注意上面那条 {@code left join requirement} **不加**这个过滤——那次连接只是
     * 为了取 {@code current_revision_id} 来匹配当前有效 Review，把它过滤掉会让
     * 修订变成 NULL、从而改写「哪个 Review 是当前有效」的判定。PR 自身的活动状态
     * 是 PR 的事实，与它的需求是否还在产品面上无关。
     */
    List<Long> requirementIds(long projectId) {
        return jdbc.queryForList(
                "select id from requirement where project_id = ? and deleted_at is null order by id",
                Long.class, projectId);
    }

    /**
     * 一个 PR、它所关联的需求（没有则为 null），
     * 以及它当前有效 Review 的执行状态与决策（没有则两者皆为 null）。
     */
    record CurrentReview(Long requirementId, ReviewStatus status, ReviewDecision decision) {

        boolean hasCurrentReview() {
            return status != null;
        }
    }
}
