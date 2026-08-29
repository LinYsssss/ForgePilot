package com.forgepilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.review.ReviewViews.CalibrationBin;
import com.forgepilot.review.ReviewViews.Interval;
import com.forgepilot.review.ReviewViews.RequirementCoverage;
import com.forgepilot.review.ReviewViews.ReviewCalibration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 覆盖度与校准这两个派生视图。
 *
 * <p>这里只锁四件删掉就会静默出错的事：区间在没有样本时说「没有区间」而不是零、
 * 校准的标签取自<strong>首次</strong>人工裁决、覆盖度只反映<strong>当前修订</strong>、
 * 以及这两个端点真的挂在它们声称的那条 HTTP 路径上。
 */
@SpringBootTest
class ReviewInsightTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private ReviewInsightService insights;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ------------------------------------------------------------------ Wilson

    /**
     * 没有样本时没有区间。
     *
     * <p>返回 {@code [0, 0]} 会被读成「测过了，确认率为零」——而实际含义是
     * 「还没有任何人裁决过」。这两句话在一张校准表上导向完全相反的结论。
     */
    @Test
    void anEmptyBinHasNoIntervalAtAll() {
        assertThat(Wilson.interval(0, 0)).isNull();
    }

    /**
     * 零次成功仍然给出一个非零宽度的区间。
     *
     * <p>这一条同时是「用的是 Wilson 而不是 Wald」的判据：正态近似在比例恰为 0 时
     * 给出宽度为零的 {@code [0, 0]}，恰好在样本最少、最该显出不确定性的时候
     * 假装自己很确定。
     */
    @Test
    void zeroSuccessesStillLeaveRoomAbove() {
        Interval interval = Wilson.interval(0, 1);

        assertThat(interval).isNotNull();
        assertThat(interval.low()).isZero();
        assertThat(interval.high()).isGreaterThan(0.5);
    }

    /** 端点被夹在 [0,1] 内：概率没有负数，也不会超过一。 */
    @Test
    void theIntervalStaysInsideTheUnitRange() {
        Interval allConfirmed = Wilson.interval(5, 5);

        assertThat(allConfirmed).isNotNull();
        assertThat(allConfirmed.low()).isGreaterThan(0.0).isLessThan(1.0);
        assertThat(allConfirmed.high()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------- calibration

    /**
     * <strong>这一条是整张校准表正确性的支点。</strong>
     *
     * <p>一条被驳回的 finding 可以再被重开（{@code REJECTED -> OPEN}），之后走到
     * {@code CONFIRMED}。若按<em>当前状态</em>打标签，这条样本就会从「模型报错了」
     * 翻成「模型报对了」——而人第一次的判断才是对模型那次输出的评价。
     * 写反了整张图的方向都是错的，且不会有任何报错。
     */
    @Test
    void theLabelComesFromTheFirstAdjudicationNotTheCurrentStatus() {
        Scenario scenario = new Scenario();
        long finding = scenario.finding(FindingConfidence.HIGH, FindingStatus.CONFIRMED, 1L);
        scenario.event(finding, FindingAction.REJECT, FindingStatus.OPEN, FindingStatus.REJECTED);
        scenario.event(finding, FindingAction.CONFIRM, FindingStatus.REJECTED, FindingStatus.OPEN);
        scenario.event(finding, FindingAction.CONFIRM, FindingStatus.OPEN, FindingStatus.CONFIRMED);

        CalibrationBin high = binOf(scenario.calibration(), FindingConfidence.HIGH);

        assertThat(high.adjudicated()).isOne();
        assertThat(high.confirmed()).as("首次裁决是驳回，重开之后的确认不改写它").isZero();
    }

    /**
     * 三个分箱恒定全部返回，哪怕一个样本都没有。
     *
     * <p>缺行会被读成「模型从没给过这一档」，而实际含义是「这一档还没有人裁决过」。
     */
    @Test
    void everyConfidenceBinIsReportedEvenWithoutSamples() {
        Scenario scenario = new Scenario();

        ReviewCalibration calibration = scenario.calibration();

        assertThat(calibration.bins()).extracting(CalibrationBin::confidence)
                .containsExactly(FindingConfidence.values());
        assertThat(calibration.bins()).allSatisfy(bin -> {
            assertThat(bin.confirmedRate()).isNull();
            assertThat(bin.interval()).isNull();
        });
    }

    /**
     * 一张空表要能说清自己为什么空。
     *
     * <p>「还没人裁决」与「这些 finding 产自更早的 prompt 版本、本就没有置信度」
     * 是两种完全不同的空，处置方式也不同：前者等人干活，后者要重跑审查。
     */
    @Test
    void anEmptyTableExplainsWhichKindOfEmptyItIs() {
        Scenario scenario = new Scenario();
        scenario.finding(FindingConfidence.LOW, FindingStatus.OPEN, 1L);
        scenario.finding(null, FindingStatus.OPEN, 1L);
        scenario.finding(null, FindingStatus.OPEN, 2L);

        ReviewCalibration calibration = scenario.calibration();

        assertThat(calibration.awaitingAdjudication()).isOne();
        assertThat(calibration.withoutConfidence()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- coverage

    /**
     * 覆盖度只反映当前修订。
     *
     * <p>发布新修订之后，旧修订上的裁定针对的是另一套验收条件；把它们留在页面上，
     * 展示的就是一组对不上号的结论。所以正确答案是回到「尚未审查」，而不是沿用旧裁定。
     */
    @Test
    void publishingANewRevisionResetsCoverageInsteadOfReusingOldVerdicts() {
        Scenario scenario = new Scenario();
        scenario.completedReview(scenario.revisionId, "[{\"acKey\":\"AC-1\",\"verdict\":\"COVERED\"}]");
        assertThat(scenario.coverage().criteria())
                .singleElement()
                .satisfies(row -> assertThat(row.verdict()).isEqualTo(AcVerdict.COVERED));

        scenario.publishNewRevision();

        RequirementCoverage after = scenario.coverage();
        assertThat(after.lastReviewId()).as("新修订还没有被审查过").isNull();
        assertThat(after.criteria()).singleElement()
                .satisfies(row -> assertThat(row.verdict())
                        .as("没有裁定不等于 NOT_FOUND").isNull());
    }

    /** 未决 finding 数只数当前那一次审查，不跨审查累加同一个被延续的问题。 */
    @Test
    void openFindingsAreCountedAgainstTheLatestReviewOnly() {
        Scenario scenario = new Scenario();
        long first = scenario.completedReview(scenario.revisionId, "[]");
        scenario.findingIn(first, FindingStatus.OPEN, 1L);
        long second = scenario.completedReview(scenario.revisionId, "[]");
        scenario.findingIn(second, FindingStatus.OPEN, 1L);

        assertThat(scenario.coverage().criteria()).singleElement()
                .satisfies(row -> assertThat(row.openFindings()).isOne());
    }

    // -------------------------------------------------------------------- HTTP

    /**
     * 两个端点真的挂在它们声称的路径上。
     *
     * <p>本项目有过教训：只有 Service 测试时，路径写错会在后端全绿而在前端 404。
     */
    @Test
    void bothViewsAreReachableOverTheRealChain() throws Exception {
        Scenario scenario = new Scenario();
        scenario.completedReview(scenario.revisionId, "[{\"acKey\":\"AC-1\",\"verdict\":\"AT_RISK\"}]");

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/projects/{p}/requirements/{r}/coverage",
                                scenario.projectId, scenario.requirementId)
                        .with(user(scenario.memberName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criteria[0].acKey").value("AC-1"))
                .andExpect(jsonPath("$.criteria[0].verdict").value("AT_RISK"));

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/projects/{p}/review-calibration", scenario.projectId)
                        .with(user(scenario.memberName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bins.length()").value(FindingConfidence.values().length));
    }

    /** 非成员看到的是 404，与项目不存在时完全相同——不得由状态码泄露存在性。 */
    @Test
    void aNonMemberCannotTellTheProjectApart() throws Exception {
        Scenario scenario = new Scenario();
        String outsider = usernameOf(account("outsider"));

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/projects/{p}/requirements/{r}/coverage",
                                scenario.projectId, scenario.requirementId)
                        .with(user(outsider)))
                .andExpect(status().isNotFound());
        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/projects/{p}/review-calibration", scenario.projectId)
                        .with(user(outsider)))
                .andExpect(status().isNotFound());
    }

    // ----------------------------------------------------------------- helpers

    private static CalibrationBin binOf(ReviewCalibration calibration, FindingConfidence confidence) {
        return calibration.bins().stream()
                .filter(bin -> bin.confidence() == confidence)
                .findFirst()
                .orElseThrow();
    }

    private long account(String role) {
        return jdbc.queryForObject("insert into user_account (username, display_name, password_hash) "
                + "values (?, 'Test User', 'bcrypt-placeholder') returning id", Long.class,
                "insight-" + role + "-" + SEQUENCE.incrementAndGet());
    }

    private String usernameOf(long userId) {
        return jdbc.queryForObject("select username from user_account where id = ?", String.class, userId);
    }

    /** 一个项目、一个成员、一条带单条验收条件的需求，以及挂 finding 用的一个 PR。 */
    private final class Scenario {

        private final long member = account("member");
        private final String memberName = usernameOf(member);
        private final long projectId;
        private final long requirementId;
        private final long pullRequestId;

        private long revisionId;
        private long reviewId;

        private Scenario() {
            int ordinal = SEQUENCE.incrementAndGet();
            projectId = jdbc.queryForObject("insert into project (name, created_by, status) "
                    + "values (?, ?, 'ACTIVE') returning id", Long.class, "insight-" + ordinal, member);
            jdbc.update("with m as (insert into project_member (project_id, user_id) values (?, ?) "
                    + "returning project_id, user_id) insert into project_member_role "
                    + "(project_id, user_id, role) select project_id, user_id, 'LEADER' from m",
                    projectId, member);

            requirementId = jdbc.queryForObject("insert into requirement (project_id, status) "
                    + "values (?, 'DRAFT') returning id", Long.class, projectId);
            revisionId = revision(1);

            long repositoryId = jdbc.queryForObject("insert into scm_repository (project_id, provider, "
                    + "instance_identity, external_id, api_base, encrypted_token, encrypted_secret) "
                    + "values (?, 'GITHUB', 'github.com', ?, 'https://api.github.com', 'x', 'y') "
                    + "returning id", Long.class, projectId, "repo-" + UUID.randomUUID());
            pullRequestId = jdbc.queryForObject("insert into pull_request (project_id, repository_id, "
                    + "external_number, base_sha, head_sha, review_input_fingerprint, changed_files, "
                    + "author_external_user_id, author_username) "
                    + "values (?, ?, 1, 'base-1', 'head-1', 'fp-1', '[]'::jsonb, 'gh-1', 'octocat') "
                    + "returning id", Long.class, projectId, repositoryId);
            reviewId = completedReview(revisionId, "[]");
        }

        /** 一个修订加它那条 {@code AC-1}，并把需求的当前修订指过去。 */
        private long revision(int seq) {
            long id = jdbc.queryForObject("insert into requirement_revision (project_id, requirement_id, "
                    + "seq, title, created_by) values (?, ?, ?, ?, ?) returning id", Long.class,
                    projectId, requirementId, seq, "Revision " + seq, member);
            jdbc.update("insert into acceptance_criterion (project_id, requirement_revision_id, "
                    + "ac_key, sort_order, text) values (?, ?, 'AC-1', 1, '发货前必须校验支付状态')",
                    projectId, id);
            jdbc.update("update requirement set current_revision_id = ? where id = ?", id, requirementId);
            return id;
        }

        private void publishNewRevision() {
            revisionId = revision(2);
        }

        /** 每次调用换一个 head，因为审查的身份四元组带唯一约束。 */
        private long completedReview(long revision, String acVerdicts) {
            String head = "head-" + SEQUENCE.incrementAndGet();
            reviewId = jdbc.queryForObject("insert into review (project_id, pull_request_id, head_sha, "
                    + "review_input_fingerprint, requirement_id, requirement_revision_id, status, "
                    + "execution_attempt, summary_json) "
                    + "values (?, ?, ?, ?, ?, ?, 'COMPLETED', 1, cast(? as jsonb)) returning id",
                    Long.class, projectId, pullRequestId, head, "fp-" + head, requirementId, revision,
                    "{\"acVerdicts\":" + acVerdicts + "}");
            return reviewId;
        }

        private long finding(FindingConfidence confidence, FindingStatus status, Long acId) {
            return findingIn(reviewId, status, acId, confidence);
        }

        private long findingIn(long review, FindingStatus status, Long acId) {
            return findingIn(review, status, acId, FindingConfidence.HIGH);
        }

        private long findingIn(long review, FindingStatus status, Long acId,
                FindingConfidence confidence) {
            Long criterionId = acId == null ? null : jdbc.queryForObject(
                    "select id from acceptance_criterion where requirement_revision_id = ? "
                            + "order by id limit 1", Long.class, revisionId);
            return jdbc.queryForObject("insert into finding (project_id, review_id, review_attempt, "
                    + "requirement_id, requirement_revision_id, ac_id, finding_type, path, line, "
                    + "evidence, confidence, status, finding_key, evidence_hash, basis_hash, continuity) "
                    + "values (?, ?, 1, ?, ?, ?, 'REQUIREMENT', 'src/Main.java', 12, 'the evidence', "
                    + "?, ?, ?, 'evidence-hash', 'basis-hash', 'NEW') returning id", Long.class,
                    projectId, review, requirementId, revisionId, criterionId,
                    confidence == null ? null : confidence.name(), status.name(),
                    "key-" + SEQUENCE.incrementAndGet());
        }

        private void event(long finding, FindingAction action, FindingStatus from, FindingStatus to) {
            jdbc.update("insert into finding_event (project_id, finding_id, actor_id, action, "
                    + "from_status, to_status) values (?, ?, ?, ?, ?, ?)",
                    projectId, finding, member, action.name(), from.name(), to.name());
        }

        private RequirementCoverage coverage() {
            return insights.coverage(projectId, member, requirementId);
        }

        private ReviewCalibration calibration() {
            return insights.calibration(projectId, member);
        }
    }
}
