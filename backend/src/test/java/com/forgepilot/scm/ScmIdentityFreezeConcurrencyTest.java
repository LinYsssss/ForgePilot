package com.forgepilot.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.forgepilot.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The concurrent half of the stable-identity freeze (AC15). Single-threaded
 * coverage lives in
 * {@code ScmRepositoryApiTest.oncePullRequestsExistTheIdentityIsFrozenButTheApiBaseStillMoves},
 * and that runs on one thread, where the row lock can do nothing because nobody is
 * contending for it.
 *
 * <p>The freeze is a cross-row rule — whether {@code scm_repository}'s identity
 * columns may change depends on {@code pull_request} having no rows — so no
 * immediate constraint expresses it and the whole guarantee rests on
 * {@code findWithLockByProjectIdAndId} being taken <em>before</em> the emptiness
 * check. This class pins exactly that ordering by holding the row lock from a
 * second connection and committing the first pull request while the update is
 * queued behind it.
 *
 * <p><strong>What this class does not cover, and why it is not an oversight.</strong>
 * The freeze protects the ordering "identity change versus first pull request". It
 * does not protect the ordering "identity change versus the unlocked re-read inside
 * {@code PullRequestSyncService.apply}", and that second race is open: measured, an
 * ingestion whose {@code findById} runs before an identity update commits and whose
 * insert lands after it stores a {@code review_input_fingerprint} that cannot be
 * recomputed from the repository row it belongs to. No test here asserts that,
 * because asserting it today would assert a defect rather than a guarantee. It is
 * reported rather than pinned.
 */
@SpringBootTest
class ScmIdentityFreezeConcurrencyTest extends ScmTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    /** Allowlisted for tests, so nothing here is decided by the SSRF policy. */
    private static final String API_BASE = "http://127.0.0.1:34590";

    @Autowired
    private ScmRepositoryService service;

    @Autowired
    private ScmSecretCipher cipher;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The interleaving the single-threaded test cannot reach: the emptiness check
     * and the first pull request are concurrent.
     *
     * <p>What each assertion is worth, stated plainly. The {@link TimeoutException}
     * only proves the update cannot complete while another transaction holds the
     * row — a service that read the row without the lock would still stall, just
     * later, when Hibernate flushes the UPDATE at commit. On its own it would also
     * pass if the pool had never started the task, which is why
     * {@link #awaitBlockedOnTheRepositoryRow()} asks PostgreSQL rather than the
     * clock: the interleaving has to be observed, not assumed, or this degenerates
     * into the single-threaded case that already exists. The load-bearing
     * assertions are the two at the end: the update comes back <strong>409</strong>
     * and the identity columns are untouched. Reaching those requires the emptiness
     * check to run after the lock was granted, which is the only way it can observe
     * a pull request that did not exist when the request arrived. Drop the lock and
     * the same interleaving ends with the identity moved and a pull request sitting
     * under it — the state the freeze exists to make unreachable.
     */
    @Test
    void theUpdateWaitsForTheRowAndThenSeesThePullRequestThatCommittedWhileItWaited() throws Exception {
        Fixture fixture = new Fixture();
        Map<String, Object> before = identityOf(fixture.repository);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockRepositoryRow(holder, fixture.repository);

            Future<HttpStatus> update = pool.submit(() -> statusOf(() -> service.update(fixture.project,
                    fixture.owner, fixture.repository, null, "moved-" + SEQUENCE.incrementAndGet(),
                    null, null, null, null)));

            awaitBlockedOnTheRepositoryRow();
            assertThatThrownBy(() -> update.get(2, TimeUnit.SECONDS))
                    .as("the update must not decide anything while another transaction holds the row")
                    .isInstanceOf(TimeoutException.class);

            // The repository's first pull request arrives and commits while the
            // identity change is still queued behind the lock.
            insertPullRequest(holder, fixture, 1);
            holder.commit();

            assertThat(update.get(30, TimeUnit.SECONDS)).isEqualTo(HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }

        assertThat(identityOf(fixture.repository))
                .as("nothing about the identity may have moved")
                .isEqualTo(before);
        assertThat(pullRequestCount(fixture.repository)).isEqualTo(1);
    }

    /**
     * The control for the test above, and it exists to remove the one alternative
     * explanation of that 409: that the service simply refuses whenever it had to
     * wait. Same fixture, same lock, same waiting — only nothing arrives while the
     * update is queued, and then the change goes through whole.
     *
     * <p>Stated plainly, because a control that is mistaken for a proof is worse
     * than no control: this test would <em>not</em> go red if the row lock were
     * removed. It is not measuring the lock. It is measuring that the refusal above
     * is caused by the pull request and not by the contention.
     */
    @Test
    void waitingForTheRowIsNotItselfARefusal() throws Exception {
        Fixture fixture = new Fixture();
        String moved = "waited-" + SEQUENCE.incrementAndGet();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockRepositoryRow(holder, fixture.repository);

            Future<HttpStatus> update = pool.submit(() -> statusOf(() -> service.update(fixture.project,
                    fixture.owner, fixture.repository, null, moved, "http://127.0.0.1:34591",
                    null, null, null)));
            awaitBlockedOnTheRepositoryRow();
            assertThatThrownBy(() -> update.get(2, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

            holder.rollback();

            assertThat(update.get(30, TimeUnit.SECONDS)).as("no pull request arrived, so nothing refuses it")
                    .isNull();
        } finally {
            pool.shutdownNow();
        }

        assertThat(identityOf(fixture.repository)).isEqualTo(
                Map.<String, Object>of("external_id", moved, "instance_identity", "127.0.0.1:34591"));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Blocks until PostgreSQL itself says a backend is parked on a heavyweight lock
     * while running a statement against {@code scm_repository}. "The future has not
     * completed" is compatible with the task never having been scheduled; this is
     * not. Every connection in this test belongs to the same role, so
     * {@code pg_stat_activity.query} is readable without any elevated privilege.
     */
    private void awaitBlockedOnTheRepositoryRow() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity "
                    + "where datname = current_database() and wait_event_type = 'Lock' "
                    + "and query ilike '%scm_repository%'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the update never reached the row lock, so nothing was interleaved");
    }

    /** Exactly the lock {@code findWithLockByProjectIdAndId} takes, from outside Hibernate. */
    private static void lockRepositoryRow(Connection connection, long repository) throws SQLException {
        try (PreparedStatement select = connection
                .prepareStatement("select id from scm_repository where id = ? for update")) {
            select.setLong(1, repository);
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).as("the row to lock must exist").isTrue();
            }
        }
    }

    private static void insertPullRequest(Connection connection, Fixture fixture, int number)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into pull_request (project_id, repository_id, external_number, base_sha, head_sha, "
                        + "review_input_fingerprint, changed_files, author_external_user_id, author_username) "
                        + "values (?, ?, ?, 'base-sha', 'head-sha', 'fingerprint', '[]'::jsonb, 'gh-1', "
                        + "'octocat')")) {
            insert.setLong(1, fixture.project);
            insert.setLong(2, fixture.repository);
            insert.setInt(3, number);
            insert.executeUpdate();
        }
    }

    private Map<String, Object> identityOf(long repository) {
        return jdbc.queryForMap("select external_id, instance_identity from scm_repository where id = ?",
                repository);
    }

    private int pullRequestCount(long repository) {
        Integer value = jdbc.queryForObject("select count(*) from pull_request where repository_id = ?",
                Integer.class, repository);
        return value == null ? 0 : value;
    }

    /** The status the API would return, or null when the call was allowed to succeed. */
    private static HttpStatus statusOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (ApiException refused) {
            return refused.getStatus();
        }
    }

    /** A project with a LEADER and one repository that has no pull request yet. */
    private final class Fixture {

        private final long owner;
        private final long project;
        private final long repository;

        private Fixture() {
            int sequence = SEQUENCE.incrementAndGet();
            this.owner = jdbc.queryForObject(
                    "insert into user_account (username, display_name, password_hash) values (?, 'Test User', 'x') returning id",
                    Long.class, "freeze-user-" + sequence);
            this.project = jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "freeze-project-" + sequence, owner);
            jdbc.update("with member as (insert into project_member (project_id, user_id) values (?, ?) returning project_id, user_id) insert into project_member_role (project_id, user_id, role) select project_id, user_id, 'LEADER' from member",
                    project, owner);
            this.repository = jdbc.queryForObject(
                    "insert into scm_repository (project_id, provider, instance_identity, external_id, "
                            + "api_base, encrypted_token, encrypted_secret) values (?, 'GITHUB', "
                            + "'127.0.0.1:34590', ?, ?, ?, ?) returning id",
                    Long.class, project, "freeze-repo-" + sequence, API_BASE,
                    cipher.encrypt("token"), cipher.encrypt("secret"));
        }
    }
}
