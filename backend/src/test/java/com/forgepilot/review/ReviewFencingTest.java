package com.forgepilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.review.ReviewExecutor.Claim;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The fence, from the point of view of a worker that has already lost its lease.
 *
 * <p>ARCHITECTURE.md 3.2 lists four things such a worker must not be able to do,
 * and each of them gets its own assertion here: finish, fail, renew, and insert a
 * Finding. Asserting only the first is the easy green this batch was warned
 * about — measured, the unfenced {@code fail} is a real hole and it is the one
 * people forget.
 *
 * <p>Reviews are inserted directly rather than through {@code requestReview}, so
 * that the pool cannot claim them behind the test's back. The row this writes is
 * exactly the row the in-transaction listener writes: PENDING, attempt 0, no
 * token and no lease.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
// The background reconciliation pass would otherwise race the deliberately
// expired leases below. Its behaviour is asserted directly in ReviewEngineTest.
@TestPropertySource(properties = "forgepilot.review.reconciliation-interval-ms=3600000")
class ReviewFencingTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private ReviewExecutor executor;

    @Autowired
    private ReviewClaimRepository claims;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    // ------------------------------------------------- the four forbidden writes

    @Test
    void aWorkerWhoseLeaseExpiredCannotCompleteTheReview() {
        Fixture fixture = new Fixture();
        Claim stale = fixture.claim();
        fixture.expireLease();
        Claim live = fixture.claim();

        assertThat(rowsAffected(repository ->
                repository.complete(fixture.project, fixture.review, stale.token().toString()))).isZero();
        assertThat(fixture.status()).isEqualTo("RUNNING");

        // The control: the live claim's identical statement affects exactly one row,
        // so what refused the first one was the fence and not the statement.
        assertThat(rowsAffected(repository ->
                repository.complete(fixture.project, fixture.review, live.token().toString()))).isEqualTo(1);
        assertThat(fixture.status()).isEqualTo("COMPLETED");
    }

    @Test
    void aWorkerWhoseLeaseExpiredCannotMarkTheReviewFailed() {
        Fixture fixture = new Fixture();
        Claim stale = fixture.claim();
        fixture.expireLease();
        Claim live = fixture.claim();

        // The most commonly missed of the four. An unfenced statement here lets a
        // dead attempt fail the attempt that replaced it, and the review then looks
        // like a genuine failure that a human is invited to retry.
        assertThat(rowsAffected(repository ->
                repository.fail(fixture.project, fixture.review, stale.token().toString()))).isZero();
        assertThat(fixture.status()).isEqualTo("RUNNING");
        assertThat(fixture.attempt()).isEqualTo(live.attempt());

        assertThat(executor.fail(live)).isTrue();
        assertThat(fixture.status()).isEqualTo("FAILED");
    }

    @Test
    void aWorkerWhoseLeaseExpiredCannotRenew() {
        Fixture fixture = new Fixture();
        Claim stale = fixture.claim();
        fixture.expireLease();
        Claim live = fixture.claim();

        assertThat(rowsAffected(repository ->
                repository.renew(fixture.project, fixture.review, stale.token().toString(), 300))).isZero();
        assertThat(executor.renew(live)).isTrue();
    }

    /**
     * The fourth path is the one application code cannot close. Measured, checking
     * the attempt in Java and then inserting has a real window: the check passes,
     * the lease is taken away, and the insert still lands under the live Review.
     * So the refusal has to come from the database, and this asserts that it does
     * by naming the constraint that produced it.
     */
    @Test
    void aWorkerWhoseLeaseExpiredIsRefusedByTheDatabaseWhenItInsertsAFinding() {
        Fixture fixture = new Fixture();
        Claim stale = fixture.claim();
        fixture.expireLease();
        Claim live = fixture.claim();

        assertThatThrownBy(() -> fixture.insertFinding(stale.attempt(), "stale"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_finding_review");

        fixture.insertFinding(live.attempt(), "live");
        assertThat(fixture.findingKeys()).containsExactly("live");
    }

    // ------------------------------------------------------- the fence's own cost

    /**
     * The price of fencing at the database: a crashed attempt's findings hold the
     * attempt number in a foreign key, so nothing can increment it while they
     * exist. Left unhandled that is permanent — the only worker that could clear it
     * is the one that died. The claim therefore discards them in the same
     * transaction, and the first half of this test shows what happens without that.
     */
    @Test
    void findingsLeftBehindByACrashedAttemptDoNotPinTheReview() {
        Fixture fixture = new Fixture();
        Claim crashed = fixture.claim();
        fixture.insertFinding(crashed.attempt(), "half-written");
        fixture.expireLease();

        // The counterexample: incrementing the attempt on its own is refused.
        assertThatThrownBy(() -> jdbc.update(
                "update review set execution_attempt = execution_attempt + 1 where id = ?", fixture.review))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_finding_review");

        Optional<Claim> recovered = executor.claim(fixture.project, fixture.review);

        assertThat(recovered).isPresent();
        assertThat(recovered.orElseThrow().attempt()).isEqualTo(crashed.attempt() + 1);
        assertThat(fixture.findingKeys()).isEmpty();
    }

    /**
     * And the reverse: discarding must never reach history. A COMPLETED Review is
     * not claimable at all, so the delete's guard never fires on one and its
     * findings are the record of what was reviewed.
     */
    @Test
    void aCompletedReviewCannotBeClaimedAndItsFindingsAreUntouched() {
        Fixture fixture = new Fixture();
        Claim claim = fixture.claim();
        fixture.insertFinding(claim.attempt(), "reported");
        assertThat(executor.complete(claim)).isTrue();

        assertThat(executor.claim(fixture.project, fixture.review)).isEmpty();
        assertThat(fixture.status()).isEqualTo("COMPLETED");
        assertThat(fixture.attempt()).isEqualTo(claim.attempt());
        assertThat(fixture.findingKeys()).containsExactly("reported");
    }

    // --------------------------------------------------------------- the race

    /**
     * Two threads, one expired lease. The loser blocks on the row lock, and once
     * the winner commits it re-evaluates the WHERE clause against the new version —
     * a lease in the future — and matches nothing. Running these one after the
     * other would prove nothing: the claim is only interesting while both are in
     * flight.
     */
    @Test
    void twoThreadsRacingTheSameExpiredLeaseProduceExactlyOneWinner() throws Exception {
        Fixture fixture = new Fixture();
        fixture.claim();
        fixture.expireLease();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<Claim>> one = pool.submit(claimWhenReleased(start, fixture));
            Future<Optional<Claim>> two = pool.submit(claimWhenReleased(start, fixture));
            start.countDown();

            List<Optional<Claim>> outcomes = List.of(
                    one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));

            assertThat(outcomes).filteredOn(Optional::isPresent).hasSize(1);
            assertThat(fixture.attempt()).isEqualTo(2);
            assertThat(fixture.status()).isEqualTo("RUNNING");
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------- helpers

    private Callable<Optional<Claim>> claimWhenReleased(CountDownLatch start, Fixture fixture) {
        return () -> {
            start.await();
            return executor.claim(fixture.project, fixture.review);
        };
    }

    /**
     * Runs one repository statement in its own transaction and returns its affected
     * row count, which is the thing under test: "zero rows" is the fence, and a
     * boolean would hide the difference between zero and two.
     */
    private int rowsAffected(java.util.function.ToIntFunction<ReviewClaimRepository> statement) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        Integer affected = template.execute(status -> statement.applyAsInt(claims));
        return affected == null ? -1 : affected;
    }

    /**
     * A project with one member, one repository and one pull request that carries
     * no requirement, plus a PENDING Review over its current identity. No
     * requirement on purpose: it keeps every Finding's context columns null, which
     * is what the parent Review's own null context requires.
     */
    private final class Fixture {

        private final long project;
        private final long review;

        private Fixture() {
            int sequence = SEQUENCE.incrementAndGet();
            long owner = jdbc.queryForObject(
                    "insert into user_account (username, display_name, password_hash) values (?, 'Test User', 'x') returning id",
                    Long.class, "fencing-user-" + sequence);
            this.project = jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "fencing-project-" + sequence, owner);
            jdbc.update("with member as (insert into project_member (project_id, user_id) values (?, ?) returning project_id, user_id) insert into project_member_role (project_id, user_id, role) select project_id, user_id, 'LEADER' from member",
                    project, owner);
            long repository = jdbc.queryForObject(
                    "insert into scm_repository (project_id, provider, instance_identity, external_id, "
                            + "api_base, encrypted_token, encrypted_secret) "
                            + "values (?, 'GITHUB', ?, ?, 'http://127.0.0.1', 'x', 'y') returning id",
                    Long.class, project, "fencing-host-" + sequence, "fencing-repo-" + sequence);
            long pullRequest = jdbc.queryForObject(
                    "insert into pull_request (project_id, repository_id, external_number, base_sha, "
                            + "head_sha, review_input_fingerprint, changed_files, author_external_user_id, "
                            + "author_username) values (?, ?, 1, ?, ?, ?, '[]'::jsonb, '424242', 'octocat') "
                            + "returning id",
                    Long.class, project, repository, "base-" + sequence, "head-" + sequence,
                    "fingerprint-" + sequence);
            this.review = jdbc.queryForObject(
                    "insert into review (project_id, pull_request_id, head_sha, review_input_fingerprint, "
                            + "status) values (?, ?, ?, ?, 'PENDING') returning id",
                    Long.class, project, pullRequest, "head-" + sequence, "fingerprint-" + sequence);
        }

        private Claim claim() {
            return executor.claim(project, review).orElseThrow();
        }

        private void expireLease() {
            assertThat(jdbc.update("update review set lease_until = now() - interval '1 hour' where id = ?",
                    review)).isEqualTo(1);
        }

        private String status() {
            return jdbc.queryForObject("select status from review where id = ?", String.class, review);
        }

        private int attempt() {
            return jdbc.queryForObject("select execution_attempt from review where id = ?", Integer.class,
                    review);
        }

        private void insertFinding(int attempt, String key) {
            jdbc.update("insert into finding (project_id, review_id, review_attempt, finding_type, "
                    + "finding_key, evidence_hash, basis_hash, continuity) "
                    + "values (?, ?, ?, 'CODE_QUALITY', ?, 'e', 'b', 'NEW')",
                    project, review, attempt, key);
        }

        private List<String> findingKeys() {
            return jdbc.queryForList("select finding_key from finding where review_id = ? order by id",
                    String.class, review);
        }
    }
}
