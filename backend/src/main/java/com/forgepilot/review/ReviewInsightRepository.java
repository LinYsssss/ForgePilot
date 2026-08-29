package com.forgepilot.review;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 覆盖度与校准这两个派生视图所需的读取。
 *
 * <p>和 {@link ReviewActivityRepository} 一样用朴素 SQL，理由也一样：这两个视图都要跨
 * {@code acceptance_criterion}（归 {@code requirement} 所有）与 {@code review} / {@code finding}
 * 取数，而 {@code review} 不得注入另一个功能模块的仓库。写成 SQL 的连接不产生任何类型依赖。
 *
 * <p>{@code summary_json} 用 jsonb 运算符就地取用而不是回到 Java 里再解析一遍：那个结构归
 * 本切片所有（见 {@link ReviewViews.ReviewDetail} 的说明），在同一个包里读它不会跨越任何边界。
 */
@Repository
class ReviewInsightRepository {

    /**
     * 当前修订的全部验收条件，按展示顺序。
     *
     * <p>连接条件是 {@code r.current_revision_id = ac.requirement_revision_id}，因此它天然
     * 只返回当前修订那一套；软删的需求返回空列表。
     */
    private static final String CRITERIA_OF_CURRENT_REVISION = """
            select ac.id as ac_id, ac.ac_key as ac_key, ac.text as ac_text
              from acceptance_criterion ac
              join requirement r
                on r.project_id = ac.project_id
               and r.current_revision_id = ac.requirement_revision_id
             where ac.project_id = ? and r.id = ? and r.deleted_at is null
             order by ac.sort_order, ac.id
            """;

    /**
     * 该需求<em>当前修订</em>上最近一次跑完的审查。
     *
     * <p>限定到当前修订是承重的：旧修订上的裁定针对的是另一套验收条件，混进来就会让页面
     * 把对不上号的结论摆在一起。取 id 最大的那一行，因为审查行只增不改。
     */
    private static final String LATEST_COMPLETED_REVIEW = """
            select rv.id as review_id, rv.requirement_revision_id as revision_id
              from review rv
              join requirement r
                on r.project_id = rv.project_id
               and r.id = rv.requirement_id
             where rv.project_id = ? and rv.requirement_id = ?
               and rv.status = 'COMPLETED'
               and rv.requirement_revision_id = r.current_revision_id
             order by rv.id desc
             limit 1
            """;

    /** 那次审查对每条 AC 的裁定，从它自己的摘要里原样取出。 */
    private static final String VERDICTS_OF_REVIEW = """
            select verdict.value ->> 'acKey' as ac_key, verdict.value ->> 'verdict' as verdict
              from review rv,
                   lateral jsonb_array_elements(rv.summary_json -> 'acVerdicts') as verdict
             where rv.project_id = ? and rv.id = ?
            """;

    /**
     * 那次审查里每条 AC 当前仍未决的 finding 数。
     *
     * <p>只数<strong>这一次</strong>审查的行，不跨审查累加：一条被延续下来的问题会在每一次
     * 审查里各留一行（带 {@code carried_from_finding_id}），跨审查求和会把同一个问题数很多遍。
     */
    private static final String OPEN_FINDINGS_OF_REVIEW = """
            select ac_id, count(*) as open_findings
              from finding
             where project_id = ? and review_id = ? and status = 'OPEN' and ac_id is not null
             group by ac_id
            """;

    /**
     * 每条 finding <strong>首次</strong>被人工裁决的结果。
     *
     * <p>取首次而不是取当前状态，是因为 {@code REJECTED} 可以被重开成 {@code OPEN}
     * （见 {@link FindingStatus} 的流转表），而重开之后的当前状态会把这条样本的标签翻过来。
     * 校准要问的是「模型当时说得对不对」，那个答案在人第一次判断时就定了。
     *
     * <p>{@code DISTINCT ON} 是 PostgreSQL 语法；本项目对 PostgreSQL 15 是硬依赖。
     */
    private static final String FIRST_ADJUDICATION = """
            select distinct on (e.finding_id) e.finding_id as finding_id, e.to_status as verdict
              from finding_event e
             where e.project_id = ?
               and e.from_status = 'OPEN'
               and e.to_status in ('CONFIRMED', 'REJECTED')
             order by e.finding_id, e.id
            """;

    private static final String ADJUDICATED_BY_CONFIDENCE = """
            with first_adjudication as (%s)
            select f.confidence as confidence,
                   count(*) as adjudicated,
                   count(*) filter (where a.verdict = 'CONFIRMED') as confirmed
              from finding f
              join first_adjudication a on a.finding_id = f.id
             where f.project_id = ? and f.confidence is not null
             group by f.confidence
            """.formatted(FIRST_ADJUDICATION);

    /** 一张空校准表要能说清自己为什么空，这两个数就是那两种原因。 */
    private static final String UNUSABLE_FINDINGS = """
            with first_adjudication as (%s)
            select count(*) filter (where f.confidence is null) as without_confidence,
                   count(*) filter (where f.confidence is not null and a.finding_id is null)
                       as awaiting_adjudication
              from finding f
              left join first_adjudication a on a.finding_id = f.id
             where f.project_id = ?
            """.formatted(FIRST_ADJUDICATION);

    private final JdbcTemplate jdbc;

    ReviewInsightRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Criterion> criteriaOfCurrentRevision(long projectId, long requirementId) {
        return jdbc.query(CRITERIA_OF_CURRENT_REVISION,
                (rs, index) -> new Criterion(rs.getLong("ac_id"), rs.getString("ac_key"),
                        rs.getString("ac_text")),
                projectId, requirementId);
    }

    Optional<LatestReview> latestCompletedReview(long projectId, long requirementId) {
        return jdbc.query(LATEST_COMPLETED_REVIEW,
                (rs, index) -> new LatestReview(rs.getLong("review_id"),
                        rs.getObject("revision_id", Long.class)),
                projectId, requirementId).stream().findFirst();
    }

    /** 以 {@code ac_key} 为键：跨修订稳定的业务身份是它，而不是行 id。 */
    Map<String, AcVerdict> verdictsOf(long projectId, long reviewId) {
        Map<String, AcVerdict> verdicts = new LinkedHashMap<>();
        jdbc.query(VERDICTS_OF_REVIEW, rs -> {
            AcVerdict verdict = parseVerdict(rs.getString("verdict"));
            if (verdict != null) {
                verdicts.put(rs.getString("ac_key"), verdict);
            }
        }, projectId, reviewId);
        return verdicts;
    }

    Map<Long, Integer> openFindingsByAc(long projectId, long reviewId) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        // 花括号不是风格：{@code Map.put} 有返回值，写成表达式 lambda 会同时匹配
        // ResultSetExtractor 与 RowCallbackHandler 而编译不过。
        jdbc.query(OPEN_FINDINGS_OF_REVIEW, rs -> {
            counts.put(rs.getLong("ac_id"), rs.getInt("open_findings"));
        }, projectId, reviewId);
        return counts;
    }

    Map<FindingConfidence, Adjudication> adjudicatedByConfidence(long projectId) {
        Map<FindingConfidence, Adjudication> bins = new LinkedHashMap<>();
        jdbc.query(ADJUDICATED_BY_CONFIDENCE, rs -> {
            FindingConfidence confidence = FindingConfidence.valueOf(rs.getString("confidence"));
            bins.put(confidence, new Adjudication(rs.getLong("adjudicated"), rs.getLong("confirmed")));
        }, projectId, projectId);
        return bins;
    }

    Unusable unusableFindings(long projectId) {
        return jdbc.query(UNUSABLE_FINDINGS,
                rs -> rs.next()
                        ? new Unusable(rs.getLong("without_confidence"),
                                rs.getLong("awaiting_adjudication"))
                        : new Unusable(0, 0),
                projectId, projectId);
    }

    /**
     * 词表外的值当作「没有裁定」丢弃，而不是抛异常。理由与 {@link FindingCategory}
     * 对 category 的处理一致：摘要是一份存下来的旧数据，产生它的引擎版本可能与当前枚举
     * 不完全一致，而一个读取接口不该因此整个失败。
     */
    private static AcVerdict parseVerdict(String value) {
        if (value == null) {
            return null;
        }
        for (AcVerdict verdict : AcVerdict.values()) {
            if (verdict.name().equals(value)) {
                return verdict;
            }
        }
        return null;
    }

    record Criterion(long acId, String acKey, String text) {
    }

    record LatestReview(long reviewId, Long revisionId) {
    }

    record Adjudication(long adjudicated, long confirmed) {
    }

    record Unusable(long withoutConfidence, long awaitingAdjudication) {
    }
}
