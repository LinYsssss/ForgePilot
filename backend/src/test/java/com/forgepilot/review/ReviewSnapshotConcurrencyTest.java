package com.forgepilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.scm.PullRequestChanged;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "forgepilot.review.reconciliation-interval-ms=3600000")
class ReviewSnapshotConcurrencyTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String FILES_A = "[{\"path\":\"a.txt\",\"changeType\":\"modified\",\"patch\":\"diff A\"}]";
    private static final String FILES_B = "[{\"path\":\"b.txt\",\"changeType\":\"modified\",\"patch\":\"diff B\"}]";

    @Autowired private ReviewService reviews;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationEventPublisher publisher;
    @MockitoSpyBean private ProjectAccessService access;
    // Creation and commit are under test; no asynchronous AI work is needed.
    @MockitoBean private ReviewExecutor executor;

    @Test
    void aManualRereviewAndAWebhookEachKeepTheirOwnIdentityAndDiff() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch identityRead = new CountDownLatch(1);
        CountDownLatch captureSnapshot = new CountDownLatch(1);
        AtomicBoolean firstAuthorization = new AtomicBoolean(true);
        AtomicInteger reviewPid = new AtomicInteger();

        // Authorization runs between the real identity query and the real snapshot query.
        // Pause there without replacing either query or the database's lock behavior.
        doAnswer(call -> {
            Object member = call.callRealMethod();
            if (firstAuthorization.getAndSet(false)) {
                reviewPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                identityRead.countDown();
                assertThat(captureSnapshot.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return member;
        }).when(access).requireMember(fixture.project, fixture.owner);

        var pool = Executors.newFixedThreadPool(2);
        try {
            var manual = pool.submit(() -> reviews.requestReview(fixture.project, fixture.pullRequest, fixture.owner));
            assertThat(identityRead.await(15, TimeUnit.SECONDS)).isTrue();
            var webhook = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                jdbc.update("update pull_request set head_sha = 'head-B', review_input_fingerprint = 'input-B', "
                        + "changed_files = ?::jsonb where project_id = ? and id = ?",
                        FILES_B, fixture.project, fixture.pullRequest);
                publisher.publishEvent(new PullRequestChanged(fixture.pullRequest, "head-B", "input-B"));
                return null;
            }));

            // The fixed path blocks the webhook; the old path lets it commit B before
            // the manual request captures its context. Either interleaving is observed.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean observed = false;
            while (System.nanoTime() < deadline) {
                if (webhook.isDone() || jdbc.queryForObject("select count(*) from pg_stat_activity "
                        + "where datname = current_database() and ? = any(pg_blocking_pids(pid))",
                        Integer.class, reviewPid.get()) > 0) {
                    observed = true;
                    break;
                }
                Thread.sleep(20);
            }
            assertThat(observed).as("the webhook must either reach the held PR or commit its update").isTrue();
            captureSnapshot.countDown();
            Review review = manual.get(15, TimeUnit.SECONDS);
            webhook.get(15, TimeUnit.SECONDS);

            assertThat(review.getRequirementRevisionId()).isEqualTo(fixture.currentRevision);
            assertThat(review.getHeadSha()).isEqualTo("head-A");
            var snapshot = json.readTree(jdbc.queryForObject(
                    "select context_snapshot_json from review where id = ?", String.class, review.getId()));
            assertThat(snapshot.path("pullRequest").path("headSha").asString()).isEqualTo(review.getHeadSha());
            assertThat(snapshot.path("pullRequest").path("inputFingerprint").asString())
                    .isEqualTo(review.getReviewInputFingerprint());
            assertThat(snapshot.path("changedFiles").path(0).path("patch").asString()).isEqualTo("diff A");
            assertThat(jdbc.queryForObject("select count(*) from review where pull_request_id = ? "
                    + "and head_sha = 'head-B' and context_snapshot_json->'changedFiles'->0->>'patch' = 'diff B'",
                    Integer.class, fixture.pullRequest)).isEqualTo(1);
        } finally {
            captureSnapshot.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void simultaneousManualRequestsReuseOnePendingReview() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                start.await();
                return reviews.requestReview(fixture.project, fixture.pullRequest, fixture.owner);
            });
            var second = pool.submit(() -> {
                start.await();
                return reviews.requestReview(fixture.project, fixture.pullRequest, fixture.owner);
            });
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS).getId()).isEqualTo(second.get(15, TimeUnit.SECONDS).getId());
            assertThat(jdbc.queryForObject("select count(*) from review where pull_request_id = ? "
                    + "and requirement_revision_id = ?", Integer.class, fixture.pullRequest, fixture.currentRevision))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** An already reviewed PR whose requirement has subsequently advanced to revision 2. */
    private final class Fixture {
        private final long owner;
        private final long project;
        private final long pullRequest;
        private final long currentRevision;

        private Fixture() {
            int sequence = SEQUENCE.incrementAndGet();
            owner = jdbc.queryForObject("insert into user_account (username, display_name, password_hash) "
                    + "values (?, 'Test User', 'x') returning id", Long.class, "review-race-" + sequence);
            project = jdbc.queryForObject("insert into project (name, created_by, status) "
                    + "values (?, ?, 'ACTIVE') returning id", Long.class, "Review race " + sequence, owner);
            jdbc.update("insert into project_member (project_id, user_id) values (?, ?)", project, owner);
            jdbc.update("insert into project_member_role (project_id, user_id, role) values (?, ?, 'LEADER')", project, owner);
            long requirement = jdbc.queryForObject("insert into requirement (project_id, status) "
                    + "values (?, 'READY') returning id", Long.class, project);
            long oldRevision = revision(requirement, 1);
            currentRevision = revision(requirement, 2);
            jdbc.update("update requirement set current_revision_id = ? where id = ?", currentRevision, requirement);
            long repository = jdbc.queryForObject("insert into scm_repository (project_id, provider, instance_identity, "
                    + "external_id, api_base, encrypted_token, encrypted_secret) "
                    + "values (?, 'GITHUB', 'github.com', ?, 'https://api.github.com', 'x', 'y') returning id",
                    Long.class, project, "review-race-" + sequence);
            pullRequest = jdbc.queryForObject("insert into pull_request (project_id, repository_id, external_number, "
                    + "base_sha, head_sha, review_input_fingerprint, changed_files, requirement_id, "
                    + "author_external_user_id, author_username) "
                    + "values (?, ?, 1, 'base', 'head-A', 'input-A', ?::jsonb, ?, 'author', 'author') returning id",
                    Long.class, project, repository, FILES_A, requirement);
            jdbc.update("insert into review (project_id, pull_request_id, head_sha, review_input_fingerprint, "
                    + "requirement_id, requirement_revision_id, status) "
                    + "values (?, ?, 'head-A', 'input-A', ?, ?, 'COMPLETED')", project, pullRequest, requirement, oldRevision);
        }

        private long revision(long requirement, int seq) {
            return jdbc.queryForObject("insert into requirement_revision (project_id, requirement_id, seq, title, created_by) "
                    + "values (?, ?, ?, 'Title', ?) returning id", Long.class, project, requirement, seq, owner);
        }
    }
}
