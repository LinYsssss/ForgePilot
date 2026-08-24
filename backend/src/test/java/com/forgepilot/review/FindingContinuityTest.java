package com.forgepilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.review.FindingContinuityCalculator.Lineage;
import com.forgepilot.review.ReviewOutput.FindingCandidate;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cross-round lineage against a real PostgreSQL, because every rule in
 * ARCHITECTURE.md 3.6 is a statement about rows that already exist: which Review
 * was the previous one, which human judgement was the most recent, and which
 * pull request either of them belonged to.
 *
 * <p>Rows are written with JdbcTemplate rather than through a service, so that a
 * rule only a service enforces cannot pass for enforced, and so that these tests
 * do not depend on the parts of the engine other slices own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FindingContinuityTest extends PostgresTestBase {

    private static final String CHECK_VIOLATION = "23514";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /**
     * Unique per run, because a bare counter is only unique inside this class and
     * the whole suite shares one database. Two classes both starting at "u-1"
     * collide on uq_user_account_username, and both starting at "octo/repo-1"
     * collide on the globally unique SCM identity — which is the constraint
     * doing its job, not a constraint to work around.
     */
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    private static final String KEY =
            FindingKeys.findingKey(FindingType.CODE_QUALITY, "src/A.java", 3, "style", null, null);
    private static final String OTHER_KEY =
            FindingKeys.findingKey(FindingType.CODE_QUALITY, "src/B.java", 7, "style", null, null);
    private static final String EVIDENCE = FindingKeys.evidenceHash("class A {}");
    private static final String MOVED_EVIDENCE = FindingKeys.evidenceHash("class A { int x; }");
    private static final String BASIS = FindingKeys.basisHash("body", "AC-1", "the criterion", List.of());
    private static final String REWRITTEN_BASIS =
            FindingKeys.basisHash("body", "AC-1", "the criterion, now with a bound", List.of());

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FindingContinuityCalculator continuity;

    // ------------------------------------------------------------- persistence

    @Test
    void aFindingThePreviousCompletedRoundReportedIsPersistingAndStartsOpenAgain() {
        Fixture fixture = new Fixture();
        long previous = fixture.completedReview();
        long previousFinding = fixture.finding(previous, KEY, EVIDENCE, BASIS);
        long current = fixture.runningReview();

        Lineage lineage = lineage(fixture, current, KEY, EVIDENCE, BASIS);

        assertThat(lineage.continuity()).isEqualTo(FindingContinuity.PERSISTING);
        assertThat(lineage.carriedFromFindingId()).isEqualTo(previousFinding);
        assertThat(lineage.initialStatus())
                .as("a problem still being there is not a decision anybody made")
                .isEqualTo(FindingStatus.OPEN);
    }

    @Test
    void onlyTheImmediatelyPrecedingCompletedRoundIsCompared() {
        Fixture fixture = new Fixture();
        fixture.finding(fixture.completedReview(), KEY, EVIDENCE, BASIS);
        fixture.finding(fixture.completedReview(), OTHER_KEY, EVIDENCE, BASIS);
        long current = fixture.runningReview();

        // The round before last reported it; the previous one did not. 3.6.3
        // compares exactly one round back, so this is new again.
        assertThat(lineage(fixture, current, KEY, EVIDENCE, BASIS).continuity())
                .isEqualTo(FindingContinuity.NEW);
    }

    @Test
    void aRoundThatFailedIsNotTheRoundBefore() {
        Fixture fixture = new Fixture();
        long previousFinding = fixture.finding(fixture.completedReview(), KEY, EVIDENCE, BASIS);
        fixture.review("FAILED");
        long current = fixture.runningReview();

        Lineage lineage = lineage(fixture, current, KEY, EVIDENCE, BASIS);

        assertThat(lineage.continuity()).isEqualTo(FindingContinuity.PERSISTING);
        assertThat(lineage.carriedFromFindingId()).isEqualTo(previousFinding);
    }

    // ------------------------------------------------------------- suppression

    @Test
    void aRejectionIsInheritedOnlyWhenBothHashesAreUnchanged() {
        Fixture fixture = new Fixture();
        long previous = fixture.completedReview();
        long rejected = fixture.finding(previous, KEY, EVIDENCE, BASIS);
        fixture.reject(rejected);
        long current = fixture.runningReview();

        Lineage inherited = lineage(fixture, current, KEY, EVIDENCE, BASIS);
        // The previous round also reported this key, so PERSISTING was available and
        // lost: 3.6.5 fixes the order at SUPPRESSED > PERSISTING > NEW.
        assertThat(inherited.continuity()).isEqualTo(FindingContinuity.SUPPRESSED);
        assertThat(inherited.initialStatus()).isEqualTo(FindingStatus.REJECTED);
        assertThat(inherited.carriedFromFindingId()).isEqualTo(rejected);

        assertThat(lineage(fixture, current, KEY, MOVED_EVIDENCE, BASIS).continuity())
                .as("the code moved under the rejection, so the rejection does not survive it")
                .isEqualTo(FindingContinuity.PERSISTING);
        assertThat(lineage(fixture, current, KEY, EVIDENCE, REWRITTEN_BASIS).continuity())
                .as("the criterion it was judged against was rewritten")
                .isEqualTo(FindingContinuity.PERSISTING);
    }

    @Test
    void aRejectionThatWasReopenedIsNotSuppressedAgain() {
        Fixture fixture = new Fixture();
        long rejected = fixture.finding(fixture.completedReview(), KEY, EVIDENCE, BASIS);
        fixture.reject(rejected);
        fixture.reopen(rejected);
        long current = fixture.runningReview();

        // 3.6.4 asks for the most recent judgement, not the most recent rejection.
        // Otherwise reopening would silently undo itself on the very next round.
        assertThat(lineage(fixture, current, KEY, EVIDENCE, BASIS).continuity())
                .isEqualTo(FindingContinuity.PERSISTING);
    }

    @Test
    void neitherPersistenceNorSuppressionCrossesAPullRequest() {
        Fixture fixture = new Fixture();
        long elsewhere = fixture.completedReviewOn(fixture.otherPullRequest);
        fixture.reject(fixture.finding(elsewhere, KEY, EVIDENCE, BASIS));
        long current = fixture.runningReview();

        Lineage lineage = lineage(fixture, current, KEY, EVIDENCE, BASIS);

        assertThat(lineage.continuity())
                .as("the same key in another pull request describes another change")
                .isEqualTo(FindingContinuity.NEW);
        assertThat(lineage.carriedFromFindingId()).isNull();
    }

    // ----------------------------------------------------------- not reported

    @Test
    void whatThePreviousRoundReportedAndThisOneDidNotIsDerivedAndNeverStored() {
        Fixture fixture = new Fixture();
        long previous = fixture.completedReview();
        fixture.finding(previous, KEY, EVIDENCE, BASIS);
        fixture.finding(previous, OTHER_KEY, EVIDENCE, BASIS);
        long current = fixture.completedReview();
        long stillReported = fixture.finding(current, KEY, EVIDENCE, BASIS);

        assertThat(continuity.notReported(fixture.project, fixture.pullRequest, current))
                .extracting(Finding::getFindingKey)
                .containsExactly(OTHER_KEY);

        assertThat(jdbc.queryForObject(
                "select count(*) from finding where status = 'NOT_REPORTED'", Integer.class))
                .as("3.6.3: it is an observation about the previous round, not a row")
                .isZero();
        assertThat(sqlStateOf(() -> jdbc.update(
                "update finding set status = 'NOT_REPORTED' where id = ?", stillReported)))
                .as("and the database refuses to store it at all")
                .isEqualTo(CHECK_VIOLATION);
    }

    // ----------------------------------------------------------------- helpers

    private Lineage lineage(Fixture fixture, long reviewId, String key, String evidenceHash, String basisHash) {
        return continuity.lineageOf(fixture.project, fixture.pullRequest, reviewId,
                List.of(new FindingCandidate(FindingType.CODE_QUALITY, "src/A.java", 3, "class A {}",
                        null, null, null, null, key, evidenceHash, basisHash)))
                .get(key);
    }

    /**
     * One project, one repository and two pull requests. The Reviews carry no
     * requirement, which keeps the constraint trigger satisfied with both context
     * columns null on parent and child alike — lineage does not depend on them.
     */
    private final class Fixture {

        private final long owner;
        private final long project;
        private final long repository;
        private final long pullRequest;
        private final long otherPullRequest;

        private Fixture() {
            this.owner = requireId(jdbc.queryForObject(
                    "insert into user_account (username, display_name, password_hash) values (?, 'Test User', 'x') returning id",
                    Long.class, "u-" + RUN + "-" + SEQUENCE.incrementAndGet()));
            this.project = requireId(jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "p-" + RUN + "-" + SEQUENCE.incrementAndGet(), owner));
            jdbc.update("with member as (insert into project_member (project_id, user_id) values (?, ?) returning project_id, user_id) insert into project_member_role (project_id, user_id, role) select project_id, user_id, 'LEADER' from member",
                    project, owner);
            this.repository = requireId(jdbc.queryForObject(
                    "insert into scm_repository (project_id, provider, instance_identity, external_id, "
                            + "api_base, encrypted_token, encrypted_secret) "
                            + "values (?, 'GITHUB', 'github.com', ?, 'https://api.github.com', 'tok', 'sec') "
                            + "returning id",
                    Long.class, project, "octo/repo-" + RUN + "-" + SEQUENCE.incrementAndGet()));
            this.pullRequest = pullRequest();
            this.otherPullRequest = pullRequest();
        }

        private long pullRequest() {
            return requireId(jdbc.queryForObject(
                    "insert into pull_request (project_id, repository_id, external_number, base_sha, "
                            + "head_sha, review_input_fingerprint, changed_files, "
                            + "author_external_user_id, author_username) "
                            + "values (?, ?, ?, 'base', 'head', 'fp', '[]'::jsonb, 'gh-1', 'dev') returning id",
                    Long.class, project, repository, SEQUENCE.incrementAndGet()));
        }

        private long completedReview() {
            return completedReviewOn(pullRequest);
        }

        private long completedReviewOn(long pullRequestId) {
            return review(pullRequestId, "COMPLETED", null);
        }

        /** RUNNING carries a lease because ck_review_running_is_leased says a claimed review must. */
        private long runningReview() {
            return review(pullRequest, "RUNNING", "gen_random_uuid()");
        }

        private long review(String status) {
            return review(pullRequest, status, null);
        }

        private long review(long pullRequestId, String status, String token) {
            // The fingerprint varies per row: uq_review_identity would otherwise
            // refuse a second review of the same pull request at the same head.
            return requireId(jdbc.queryForObject(
                    "insert into review (project_id, pull_request_id, head_sha, review_input_fingerprint, "
                            + "status, execution_token, lease_until) values (?, ?, 'head', ?, ?, "
                            + (token == null ? "null" : token) + ", "
                            + (token == null ? "null" : "now() + interval '1 minute'") + ") returning id",
                    Long.class, project, pullRequestId, "fp-" + SEQUENCE.incrementAndGet(), status));
        }

        private long finding(long reviewId, String key, String evidenceHash, String basisHash) {
            return requireId(jdbc.queryForObject(
                    "insert into finding (project_id, review_id, review_attempt, finding_type, path, line, "
                            + "evidence, status, finding_key, evidence_hash, basis_hash, continuity) "
                            + "values (?, ?, 0, 'CODE_QUALITY', 'src/A.java', 3, 'class A {}', 'OPEN', "
                            + "?, ?, ?, 'NEW') returning id",
                    Long.class, project, reviewId, key, evidenceHash, basisHash));
        }

        private void reject(long findingId) {
            move(findingId, "REJECT", "OPEN", "REJECTED");
        }

        private void reopen(long findingId) {
            move(findingId, "REOPEN", "REJECTED", "OPEN");
        }

        private void move(long findingId, String action, String from, String to) {
            jdbc.update("update finding set status = ? where id = ?", to, findingId);
            jdbc.update("insert into finding_event (project_id, finding_id, actor_id, action, from_status, "
                    + "to_status) values (?, ?, ?, ?, ?, ?)", project, findingId, owner, action, from, to);
        }
    }

    private static long requireId(Long id) {
        assertThat(id).isNotNull();
        return id;
    }

    private static String sqlStateOf(ThrowingCallable action) {
        Throwable thrown = catchThrowable(action);
        assertThat(thrown).as("statement was expected to be rejected").isNotNull();
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(thrown);
        assertThat(cause).isInstanceOf(SQLException.class);
        return ((SQLException) cause).getSQLState();
    }
}
