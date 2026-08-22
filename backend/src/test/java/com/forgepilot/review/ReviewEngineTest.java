package com.forgepilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadPoolExecutor;

import javax.sql.DataSource;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.common.ApiException;
import com.forgepilot.review.ReviewClaimRepository.StalledReview;
import com.forgepilot.scm.PullRequestChanged;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The engine's trigger, its retry semantics, its pool and its reconciliation.
 *
 * <p>Three of these assert things a passing "a review ran" test would not
 * notice: that the concurrency limit is on the core pool size rather than the max
 * pool size, that the in-transaction half of the trigger really is in the
 * transaction, and that reconciliation's query cannot invent a Review.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
// The scheduled pass would otherwise race the deliberately backdated rows below;
// recover() is invoked directly instead, which is also the only way to assert on
// what it did.
@TestPropertySource(properties = "forgepilot.review.reconciliation-interval-ms=3600000")
class ReviewEngineTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private ReviewService reviews;

    @Autowired
    private ReviewExecutor executor;

    @Autowired
    private ReviewClaimRepository claims;

    @Autowired
    private ReviewReconciliationScheduler reconciliation;

    @Autowired
    private ThreadPoolTaskExecutor reviewWorkerPool;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private org.springframework.core.env.Environment environment;

    // ----------------------------------------------------------------- the pool

    /**
     * Read the core size of the real {@link ThreadPoolExecutor}, not the intent of
     * the configuration.
     *
     * <p>A pool only grows past its core size once the queue is full, so with an
     * unbounded queue it never grows at all — measured, {@code core=1 / max=4} kept
     * exactly one thread while eight tasks waited. That makes
     * {@code setMaxPoolSize(n)} on its own a limit that reads as n and behaves as
     * the core size, and a test that merely watches a Review finish passes against
     * it. This is the assertion that does not.
     */
    @Test
    void theConcurrencyLimitIsOnCorePoolSizeAndTheQueueIsBounded() {
        int configured = Integer.parseInt(environment.getRequiredProperty("forgepilot.review.concurrency"));
        ThreadPoolExecutor pool = reviewWorkerPool.getThreadPoolExecutor();

        assertThat(pool.getCorePoolSize())
                .as("the limit has to be here; maxPoolSize alone is unreachable behind a queue")
                .isEqualTo(configured);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(configured);
        assertThat(reviewWorkerPool.getQueueCapacity())
                .as("an unbounded queue turns rejection into unbounded backlog on a 4 GB machine")
                .isLessThan(Integer.MAX_VALUE);
    }

    // -------------------------------------------------------------- the trigger

    /**
     * The two halves, in one delivery. Inside the transaction the Review exists to
     * the writer and to nobody else; only after the commit does a worker get it.
     *
     * <p>Visibility is read over a second, raw connection rather than through
     * {@code TransactionSynchronizationManager}, because measured that manager
     * answers "transaction active" in both phases and cannot tell them apart. Under
     * READ COMMITTED a separate connection is the honest witness.
     */
    @Test
    void theReviewIsCreatedInTheTransactionAndHandedOverOnlyAfterItCommits() {
        Fixture fixture = new Fixture(false);
        TransactionTemplate template = new TransactionTemplate(transactions);

        template.execute(status -> {
            publisher.publishEvent(new PullRequestChanged(fixture.pullRequest, fixture.headSha,
                    fixture.fingerprint));
            assertThat(reviewsVisibleOnAnotherConnection(fixture.pullRequest))
                    .as("no worker may see this row before the transaction commits")
                    .isZero();
            return null;
        });

        long review = onlyReviewOf(fixture.pullRequest);
        // The hand-off happens after the commit, so the row is claimable when the
        // worker reaches it — which is the whole reason the second half exists.
        //
        // Asserted on the claim rather than on the status: since the pipeline was
        // wired, RUNNING is a state the worker passes through, not one it rests in.
        // This fixture has no AI provider configured, so the analysis fails and 3.2
        // takes the row to FAILED. The durable fact that proves the hand-off is that
        // a worker took it at all.
        awaitAttempt(review, 1);
        assertThat(tokenOf(review)).isNotNull();
    }

    /**
     * The in-transaction half must be able to destroy its caller's work, otherwise
     * ARCHITECTURE.md 3.1's "no committed state where the pull request moved and its
     * Review is missing" is only a wish. The failure used here is an event naming a
     * pull request that does not exist; what it demonstrates is participation, and
     * participation is what carries the rollback.
     */
    @Test
    void aFailureInTheInTransactionListenerRollsTheWholeTransactionBack() {
        Fixture fixture = new Fixture(false);
        long missing = fixture.pullRequest + 10_000_000L;
        TransactionTemplate template = new TransactionTemplate(transactions);

        assertThatThrownBy(() -> template.execute(status -> {
            jdbc.update("update pull_request set head_sha = 'moved' where id = ?", fixture.pullRequest);
            publisher.publishEvent(new PullRequestChanged(missing, "moved", fixture.fingerprint));
            return null;
        })).isInstanceOf(ApiException.class);

        assertThat(jdbc.queryForObject("select head_sha from pull_request where id = ?", String.class,
                fixture.pullRequest)).isEqualTo(fixture.headSha);
    }

    /**
     * And it refuses to run without one at all. {@code MANDATORY} turns "this is
     * supposed to join the SCM transaction" from a comment into a failure, because
     * the alternative — quietly opening its own — commits a Review whose pull
     * request may still roll back.
     */
    @Test
    void theInTransactionListenerRefusesToRunOutsideATransaction() {
        Fixture fixture = new Fixture(false);

        assertThatThrownBy(() -> publisher.publishEvent(
                new PullRequestChanged(fixture.pullRequest, fixture.headSha, fixture.fingerprint)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(reviewCountOf(fixture.pullRequest)).isZero();
    }

    // ------------------------------------------------------- requestReview rules

    @Test
    void aCompletedReviewIsNeverRequestedAgain() {
        Fixture fixture = new Fixture(false);
        long review = fixture.insertReview("COMPLETED");

        assertThat(statusOf(() -> reviews.requestReview(fixture.project, fixture.pullRequest, fixture.owner)))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("select status from review where id = ?", String.class, review))
                .isEqualTo("COMPLETED");
        assertThat(reviewCountOf(fixture.pullRequest)).isEqualTo(1);
    }

    /**
     * Retry reuses the row rather than creating a second one, so one identity keeps
     * the history of its attempts (3.2). A new row would also be impossible: the
     * four-tuple is unique.
     */
    @Test
    void aFailedReviewIsRetriedOnTheSameRowAndTheClaimMintsOneNewAttempt() {
        Fixture fixture = new Fixture(false);
        long failed = fixture.insertReview("FAILED");
        jdbc.update("update review set execution_attempt = 2 where id = ?", failed);

        Review retried = reviews.requestReview(fixture.project, fixture.pullRequest, fixture.owner);

        assertThat(retried.getId()).isEqualTo(failed);
        assertThat(retried.getStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(retried.getExecutionAttempt())
                .as("queuing does not double-increment the attempt before the worker claims it")
                .isEqualTo(2);
        awaitAttempt(failed, 3);
        assertThat(jdbc.queryForObject("select execution_attempt from review where id = ?", Integer.class,
                failed)).isEqualTo(3);
        assertThat(reviewCountOf(fixture.pullRequest)).isEqualTo(1);
    }

    /**
     * The null case. A pull request with no requirement has a null revision on both
     * sides of the identity lookup, and {@code =} would answer unknown there — so
     * the existing row would never be found and every request would try to insert a
     * duplicate, which the unique key would then reject. Two requests, one row.
     */
    @Test
    void aPullRequestWithNoRequirementTakesItsExistingReviewInsteadOfInsertingASecond() {
        Fixture fixture = new Fixture(false);

        Review first = reviews.requestReview(fixture.project, fixture.pullRequest, fixture.owner);
        Review second = reviews.requestReview(fixture.project, fixture.pullRequest, fixture.owner);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(first.getRequirementId()).isNull();
        assertThat(first.getRequirementRevisionId()).isNull();
        assertThat(reviewCountOf(fixture.pullRequest)).isEqualTo(1);
    }

    /** The same path with a requirement, so the null case above is a contrast and not the only case. */
    @Test
    void aPullRequestWithARequirementReviewsItsCurrentRevision() {
        Fixture fixture = new Fixture(true);

        Review review = reviews.requestReview(fixture.project, fixture.pullRequest, fixture.owner);

        assertThat(review.getRequirementId()).isEqualTo(fixture.requirement);
        assertThat(review.getRequirementRevisionId()).isEqualTo(fixture.revision);
    }

    @Test
    void aPullRequestInAnotherProjectIsIndistinguishableFromOneThatDoesNotExist() {
        Fixture mine = new Fixture(false);
        Fixture theirs = new Fixture(false);

        assertThat(statusOf(() -> reviews.requestReview(mine.project, theirs.pullRequest, mine.owner)))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(() -> reviews.requestReview(mine.project, mine.pullRequest + 10_000_000L,
                mine.owner))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reviewCountOf(theirs.pullRequest)).isZero();
    }

    /**
     * The role matrix for triggering (PRD 3): LEADER and REVIEWER anything, a
     * DEVELOPER only their own pull request. "Their own" is the provider's external
     * user id against the member's verified SCM identity, never the username (D010)
     * — the username the pull request carries here is {@code octocat} for all
     * three, so matching on it would let every one of them through.
     */
    @Test
    void aDeveloperMayOnlyTriggerTheirOwnPullRequest() {
        Fixture fixture = new Fixture(false);
        long author = fixture.member("DEVELOPER", "424242");
        long otherDeveloper = fixture.member("DEVELOPER", "999999");
        long reviewer = fixture.member("REVIEWER", null);
        long outsider = jdbc.queryForObject(
                "insert into user_account (username, password_hash) values (?, 'x') returning id",
                Long.class, "engine-outsider-" + SEQUENCE.incrementAndGet());

        assertThat(statusOf(() -> reviews.requestReview(fixture.project, fixture.pullRequest,
                otherDeveloper))).isEqualTo(HttpStatus.FORBIDDEN);
        // Not a member at all: the same answer as a project that does not exist.
        assertThat(statusOf(() -> reviews.requestReview(fixture.project, fixture.pullRequest, outsider)))
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(statusOf(() -> reviews.requestReview(fixture.project, fixture.pullRequest, author)))
                .isNull();
        assertThat(statusOf(() -> reviews.requestReview(fixture.project, fixture.pullRequest, reviewer)))
                .isNull();
        assertThat(reviewCountOf(fixture.pullRequest)).isEqualTo(1);
    }

    // ------------------------------------------------------------ reconciliation

    /**
     * Recovery and backfill are different queries, and the difference is which
     * table drives them.
     *
     * <p>The pull request below has moved to a head with no Review — the state a
     * manual requirement change also produces, and one 3.1 says must be re-reviewed
     * by hand. The backfill shape returns it, which is a Review nobody asked for
     * and an AI call nobody authorized. The recovery shape, driven by
     * {@code review} alone, structurally cannot: its result set is a subset of rows
     * that exist.
     */
    @Test
    void reconciliationRecoversStoredRowsAndCouldNotBackfillAMissingOne() {
        Fixture fixture = new Fixture(false);
        long pending = fixture.insertReview("PENDING");
        jdbc.update("update pull_request set head_sha = ? where id = ?", "moved-" + fixture.pullRequest,
                fixture.pullRequest);

        Integer backfillWouldCreate = jdbc.queryForObject("""
                select count(*) from pull_request p
                 where p.id = ?
                   and not exists (select 1 from review r
                                    where r.pull_request_id = p.id
                                      and r.head_sha = p.head_sha
                                      and r.review_input_fingerprint = p.review_input_fingerprint)
                """, Integer.class, fixture.pullRequest);
        assertThat(backfillWouldCreate)
                .as("this is the query shape ARCHITECTURE.md 3.1 forbids, and it does fire")
                .isEqualTo(1);

        assertThat(stalledIds()).as("a freshly stored PENDING row is not stalled").doesNotContain(pending);

        // Now make it genuinely stalled. The same query recovers the stored row and
        // still creates nothing.
        jdbc.update("update review set updated_at = now() - interval '1 day' where id = ?", pending);
        assertThat(stalledIds()).contains(pending);

        long reviewsBefore = jdbc.queryForObject("select count(*) from review", Long.class);
        reconciliation.recover();
        // Same reason as above: what recovery proves is that the row was taken, not
        // that it stayed RUNNING while nothing analysed it.
        awaitAttempt(pending, 1);
        assertThat(jdbc.queryForObject("select count(*) from review", Long.class)).isEqualTo(reviewsBefore);
    }

    /** The other half of the set: a claim whose worker stopped renewing. */
    @Test
    void reconciliationTakesBackAReviewWhoseLeaseExpired() {
        Fixture fixture = new Fixture(false);
        long review = fixture.insertReview("PENDING");
        int claimed = executor.claim(fixture.project, review).orElseThrow().attempt();

        assertThat(stalledIds()).as("a live lease is not stalled").doesNotContain(review);
        jdbc.update("update review set lease_until = now() - interval '1 hour' where id = ?", review);
        assertThat(stalledIds()).contains(review);

        reconciliation.recover();

        awaitAttempt(review, claimed + 1);
        // The new attempt and its new token are what recovery produced. The status is
        // not asserted: the worker that just took the row is already analysing it,
        // and with no provider configured it will reach FAILED on its own schedule.
        assertThat(tokenOf(review)).isNotNull();
    }

    /** A COMPLETED row is outside the set entirely; a FAILED one waits for a person. */
    @Test
    void reconciliationLeavesCompletedAndFailedReviewsAlone() {
        Fixture fixture = new Fixture(false);
        long completed = fixture.insertReview("COMPLETED");
        jdbc.update("update review set updated_at = now() - interval '1 day' where id = ?", completed);
        Fixture other = new Fixture(false);
        long failed = other.insertReview("FAILED");
        jdbc.update("update review set updated_at = now() - interval '1 day' where id = ?", failed);

        assertThat(stalledIds()).doesNotContain(completed, failed);
    }

    // ------------------------------------------------------------------- helpers

    private List<Long> stalledIds() {
        return claims.findStalled(600, 500).stream().map(StalledReview::getReviewId).toList();
    }

    private int reviewsVisibleOnAnotherConnection(long pullRequest) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from review where pull_request_id = ?")) {
            statement.setLong(1, pullRequest);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private long onlyReviewOf(long pullRequest) {
        return jdbc.queryForObject("select id from review where pull_request_id = ?", Long.class,
                pullRequest);
    }

    private int reviewCountOf(long pullRequest) {
        return jdbc.queryForObject("select count(*) from review where pull_request_id = ?", Integer.class,
                pullRequest);
    }

    private int attemptOf(long review) {
        return jdbc.queryForObject("select execution_attempt from review where id = ?", Integer.class,
                review);
    }

    /** The other half of the fence, and unlike the status it is not transient. */
    private String tokenOf(long review) {
        return jdbc.queryForObject("select execution_token::text from review where id = ?", String.class,
                review);
    }

    private void awaitStatus(long review, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        String seen = null;
        while (System.nanoTime() < deadline) {
            seen = jdbc.queryForObject("select status from review where id = ?", String.class, review);
            if (expected.equals(seen)) {
                return;
            }
            pause();
        }
        assertThat(seen).isEqualTo(expected);
    }

    private void awaitAttempt(long review, int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        int seen = -1;
        while (System.nanoTime() < deadline) {
            seen = attemptOf(review);
            if (seen == expected) {
                return;
            }
            pause();
        }
        assertThat(seen).isEqualTo(expected);
    }

    private static void pause() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The status the API would return, or null when the call was allowed to succeed. */
    private static HttpStatus statusOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (ApiException exception) {
            return exception.getStatus();
        }
    }

    /** A project with a LEADER, a repository and one pull request, with or without a requirement. */
    private final class Fixture {

        private final long owner;
        private final long project;
        private final long pullRequest;
        private final Long requirement;
        private final Long revision;
        private final String headSha;
        private final String fingerprint;

        private Fixture(boolean withRequirement) {
            int sequence = SEQUENCE.incrementAndGet();
            this.headSha = "head-" + sequence;
            this.fingerprint = "fingerprint-" + sequence;
            this.owner = jdbc.queryForObject(
                    "insert into user_account (username, password_hash) values (?, 'x') returning id",
                    Long.class, "engine-user-" + sequence);
            this.project = jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "engine-project-" + sequence, owner);
            jdbc.update("insert into project_member (project_id, user_id, role) values (?, ?, 'LEADER')",
                    project, owner);
            if (withRequirement) {
                this.requirement = jdbc.queryForObject(
                        "insert into requirement (project_id, status) values (?, 'READY') returning id",
                        Long.class, project);
                this.revision = jdbc.queryForObject(
                        "insert into requirement_revision (project_id, requirement_id, seq, title, "
                                + "created_by) values (?, ?, 1, 'Title', ?) returning id",
                        Long.class, project, requirement, owner);
                jdbc.update("update requirement set current_revision_id = ? where id = ?", revision,
                        requirement);
            } else {
                this.requirement = null;
                this.revision = null;
            }
            long repository = jdbc.queryForObject(
                    "insert into scm_repository (project_id, provider, instance_identity, external_id, "
                            + "api_base, encrypted_token, encrypted_secret) "
                            + "values (?, 'GITHUB', ?, ?, 'http://127.0.0.1', 'x', 'y') returning id",
                    Long.class, project, "engine-host-" + sequence, "engine-repo-" + sequence);
            this.pullRequest = jdbc.queryForObject(
                    "insert into pull_request (project_id, repository_id, external_number, base_sha, "
                            + "head_sha, review_input_fingerprint, changed_files, requirement_id, "
                            + "author_external_user_id, author_username) "
                            + "values (?, ?, 1, ?, ?, ?, '[]'::jsonb, ?, '424242', 'octocat') returning id",
                    Long.class, project, repository, "base-" + sequence, headSha, fingerprint, requirement);
        }

        /** A Review over this pull request's current identity, in a chosen state. */
        private long insertReview(String status) {
            return jdbc.queryForObject(
                    "insert into review (project_id, pull_request_id, head_sha, review_input_fingerprint, "
                            + "requirement_id, requirement_revision_id, status) "
                            + "values (?, ?, ?, ?, ?, ?, ?) returning id",
                    Long.class, project, pullRequest, headSha, fingerprint, requirement, revision, status);
        }

        /** Another member, optionally carrying the verified SCM identity D010 authorizes on. */
        private long member(String role, String scmExternalUserId) {
            long user = jdbc.queryForObject(
                    "insert into user_account (username, password_hash) values (?, 'x') returning id",
                    Long.class, "engine-member-" + SEQUENCE.incrementAndGet());
            jdbc.update("insert into project_member (project_id, user_id, role, scm_external_user_id, "
                    + "scm_username, scm_identity_verified_at) values (?, ?, ?, ?, ?, ?)",
                    project, user, role, scmExternalUserId,
                    scmExternalUserId == null ? null : "octocat",
                    scmExternalUserId == null ? null : java.sql.Timestamp.from(java.time.Instant.now()));
            return user;
        }
    }
}
