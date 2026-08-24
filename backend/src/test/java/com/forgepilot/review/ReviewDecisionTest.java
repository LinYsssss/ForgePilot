package com.forgepilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * The one-shot Review Decision: who may write it, what has to be true first, and
 * what happens when two people or a person and the SCM act at once.
 *
 * <p>Every assertion here is about authorization correctness, so the concurrent
 * cases are real threads on real connections. A single-threaded green run proves
 * nothing about this endpoint: the measurements behind design.md 6.3 showed the
 * unsafe implementation behaving perfectly until two transactions interleaved.
 */
// This class leaves the shared context in a state the next one cannot rely on:
// measured, an AuthApiTest running after it receives a 401 with no XSRF-TOKEN
// cookie at all, so every write it then attempts is refused. Both classes pass
// alone. Rather than let the next class inherit that, this one gives its context
// back at the end.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class ReviewDecisionTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private ReviewDecisionService decisions;

    /** Injected so the conditional update can be raced on its own, with nothing in front of it. */
    @Autowired
    private DecisionRepository gate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ------------------------------------------------------------- concurrency

    @Test
    void twoConcurrentApprovalsLeaveExactlyOneWinner() throws Exception {
        Scenario scenario = new Scenario();
        long review = scenario.completedReview("head-1", "fp-1", null, null);

        List<HttpStatus> outcomes = race(
                () -> decide(scenario, scenario.leader, review, ReviewDecision.APPROVE),
                () -> decide(scenario, scenario.reviewer, review, ReviewDecision.APPROVE));

        // null is the winner: the loser's conditional update matched no row.
        assertThat(outcomes).containsExactlyInAnyOrder(null, HttpStatus.CONFLICT);
        assertThat(decisionOf(review)).isEqualTo("APPROVE");
        assertThat(jdbc.queryForObject("select count(*) from review where id = ? and decision_by is not null",
                Integer.class, review)).isEqualTo(1);
    }

    @Test
    void anApprovalAndARequestForChangesRaceAndOnlyOneLands() throws Exception {
        Scenario scenario = new Scenario();
        long review = scenario.completedReview("head-1", "fp-1", null, null);

        List<HttpStatus> outcomes = race(
                () -> decide(scenario, scenario.leader, review, ReviewDecision.APPROVE),
                () -> decide(scenario, scenario.reviewer, review, ReviewDecision.REQUEST_CHANGES));

        // Both are legal final verdicts, so which one wins is whichever took the
        // pull request lock first. What must never happen is both.
        assertThat(outcomes).containsExactlyInAnyOrder(null, HttpStatus.CONFLICT);
        assertThat(decisionOf(review)).isIn("APPROVE", "REQUEST_CHANGES");
    }

    /**
     * The measurement behind design.md 6.3, turned into a test.
     *
     * <p>While the SCM holds the pull request row and is moving it from
     * {@code head-1} to {@code head-2}, the same gate written <em>without</em> the
     * row lock reads the pre-update snapshot and grants an APPROVE for a head that
     * no longer exists. That is asserted here as a control, on its own connection
     * and rolled back, so this test fails if the danger it guards against ever
     * stops being real. The production path then runs against the same interleaving
     * and must refuse.
     */
    @Test
    void aDecisionCannotBeGrantedAgainstAHeadTheScmIsMovingPast() throws Exception {
        Scenario scenario = new Scenario();
        long review = scenario.completedReview("head-1", "fp-1", null, null);

        CountDownLatch scmHoldsTheRow = new CountDownLatch(1);
        CountDownLatch scmMayCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Void> scm = pool.submit(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    // The same lock the SCM's own writer takes before rolling head
                    // forward, which is what lets a decision serialise against it.
                    try (PreparedStatement lock = connection.prepareStatement(
                            "select head_sha from pull_request where project_id = ? and id = ? for update")) {
                        lock.setLong(1, scenario.projectId);
                        lock.setLong(2, scenario.pullRequestId);
                        lock.executeQuery().close();
                    }
                    try (PreparedStatement push = connection.prepareStatement(
                            "update pull_request set head_sha = 'head-2', review_input_fingerprint = 'fp-2' "
                                    + "where project_id = ? and id = ?")) {
                        push.setLong(1, scenario.projectId);
                        push.setLong(2, scenario.pullRequestId);
                        push.executeUpdate();
                    }
                    scmHoldsTheRow.countDown();
                    assertThat(scmMayCommit.await(30, TimeUnit.SECONDS)).isTrue();
                    connection.commit();
                }
                return null;
            });

            assertThat(scmHoldsTheRow.await(30, TimeUnit.SECONDS)).isTrue();

            // Control: the identical gate minus the row lock, in a transaction that
            // is thrown away. It approves head-1 while the pull request is already
            // on head-2, which is exactly the hole the lock closes.
            assertThat(gateWithoutTheRowLock(scenario, review)).isEqualTo(1);

            Future<HttpStatus> decision = pool.submit(
                    () -> decide(scenario, scenario.leader, review, ReviewDecision.APPROVE));

            // The production path must be queued behind the SCM transaction rather
            // than reading around it. Without this the test would still pass if the
            // decision merely ran after the commit.
            assertThat(waitsForThePullRequestRow()).isTrue();
            scmMayCommit.countDown();

            assertThat(decision.get(30, TimeUnit.SECONDS)).isEqualTo(HttpStatus.CONFLICT);
            scm.get(30, TimeUnit.SECONDS);
        } finally {
            scmMayCommit.countDown();
            pool.shutdownNow();
        }

        assertThat(decisionOf(review)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select head_sha from pull_request where id = ?", String.class,
                scenario.pullRequestId)).isEqualTo("head-2");
    }

    /**
     * The two tests above are satisfied by the lock-then-read order alone: the loser
     * blocks on the pull request row, re-reads the Review afterwards and is refused
     * by precondition 2 before the update is even attempted. So they do not, on their
     * own, show that the conditional update is load-bearing.
     *
     * <p>This one does. It calls the statement directly from two transactions with no
     * preconditions checked in front of it, so the only thing that can make one of
     * them affect zero rows is {@code decision = 'PENDING'} inside the {@code WHERE}
     * clause. Replace it with an unconditional save and this fails while the two
     * above still pass.
     */
    @Test
    void theConditionalUpdateIsItselfTheGate() throws Exception {
        Scenario scenario = new Scenario();
        long review = scenario.completedReview("head-1", "fp-1", null, null);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        List<Integer> affected = race(
                () -> transactions.execute(status -> gate.decideIfStillPending(scenario.projectId,
                        review, "APPROVE", scenario.leader, null)),
                () -> transactions.execute(status -> gate.decideIfStillPending(scenario.projectId,
                        review, "REQUEST_CHANGES", scenario.reviewer, null)));

        assertThat(affected).containsExactlyInAnyOrder(1, 0);
        assertThat(decisionOf(review)).isIn("APPROVE", "REQUEST_CHANGES");
    }

    // ------------------------------------------------------------ preconditions

    @Test
    void eachOfTheSixPreconditionsRefusesOnItsOwn() {
        // 1. The review has not finished running.
        Scenario running = new Scenario();
        long pending = running.review("head-1", "fp-1", null, null, "PENDING");
        assertThat(decide(running, running.leader, pending, ReviewDecision.APPROVE))
                .isEqualTo(HttpStatus.CONFLICT);

        // 2. It already carries a verdict, and a verdict is written once.
        Scenario decided = new Scenario();
        long twice = decided.completedReview("head-1", "fp-1", null, null);
        assertThat(decide(decided, decided.leader, twice, ReviewDecision.APPROVE)).isNull();
        assertThat(decide(decided, decided.reviewer, twice, ReviewDecision.REQUEST_CHANGES))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(decisionOf(twice)).isEqualTo("APPROVE");

        // 3. The head has moved.
        Scenario movedHead = new Scenario();
        long staleHead = movedHead.completedReview("head-1", "fp-1", null, null);
        movedHead.moveTo("head-2", "fp-1");
        assertThat(decide(movedHead, movedHead.leader, staleHead, ReviewDecision.APPROVE))
                .isEqualTo(HttpStatus.CONFLICT);

        // 4. The diff was re-synced under the same head.
        Scenario movedDiff = new Scenario();
        long staleDiff = movedDiff.completedReview("head-1", "fp-1", null, null);
        movedDiff.moveTo("head-1", "fp-2");
        assertThat(decide(movedDiff, movedDiff.leader, staleDiff, ReviewDecision.APPROVE))
                .isEqualTo(HttpStatus.CONFLICT);

        // 5. The requirement has a newer revision than the one reviewed, and the
        //    asymmetric case where the review has none at all but the pull request
        //    now does. Both directions matter: IS NOT DISTINCT FROM has to accept
        //    two NULLs and reject one.
        Scenario movedRevision = new Scenario();
        long requirement = movedRevision.requirement();
        long firstRevision = movedRevision.currentRevisionOf(requirement);
        movedRevision.link(requirement);
        long staleRevision = movedRevision.completedReview("head-1", "fp-1", requirement, firstRevision);
        long unversioned = movedRevision.completedReview("head-1", "fp-2", null, null);
        movedRevision.publishRevision(requirement);
        assertThat(decide(movedRevision, movedRevision.leader, staleRevision, ReviewDecision.APPROVE))
                .isEqualTo(HttpStatus.CONFLICT);
        movedRevision.moveTo("head-1", "fp-2");
        assertThat(decide(movedRevision, movedRevision.leader, unversioned, ReviewDecision.APPROVE))
                .isEqualTo(HttpStatus.CONFLICT);

        // 6. The head already carries a REQUEST_CHANGES.
        Scenario blocked = new Scenario();
        long refused = blocked.completedReview("head-1", "fp-1", null, null);
        assertThat(decide(blocked, blocked.reviewer, refused, ReviewDecision.REQUEST_CHANGES)).isNull();
        long second = blocked.completedReview("head-1", "fp-2", null, null);
        blocked.moveTo("head-1", "fp-2");
        assertThat(decide(blocked, blocked.leader, second, ReviewDecision.APPROVE))
                .isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * Precondition 5 written with {@code =} instead of {@code IS NOT DISTINCT FROM}
     * makes this impossible: two NULLs compare to unknown, the conditional update
     * matches nothing, and a pull request that implements no tracked requirement can
     * never be decided at all.
     */
    @Test
    void aPullRequestWithNoRequirementCanStillBeDecided() {
        Scenario scenario = new Scenario();
        long review = scenario.completedReview("head-1", "fp-1", null, null);

        assertThat(decide(scenario, scenario.leader, review, ReviewDecision.APPROVE)).isNull();
        assertThat(decisionOf(review)).isEqualTo("APPROVE");
    }

    // ------------------------------------------------------------ decision gate

    @Test
    void changesRequestedSticksToTheHeadAndReturnsAfterAForcePushBack() {
        Scenario scenario = new Scenario();
        long onHeadOne = scenario.completedReview("head-1", "fp-1", null, null);
        assertThat(decide(scenario, scenario.reviewer, onHeadOne, ReviewDecision.REQUEST_CHANGES)).isNull();

        // Same head, a different review identity: still blocked.
        long alsoOnHeadOne = scenario.completedReview("head-1", "fp-2", null, null);
        scenario.moveTo("head-1", "fp-2");
        assertThat(decide(scenario, scenario.leader, alsoOnHeadOne, ReviewDecision.APPROVE))
                .isEqualTo(HttpStatus.CONFLICT);

        // A new head, and only a new head, lifts it.
        scenario.moveTo("head-2", "fp-3");
        long onHeadTwo = scenario.completedReview("head-2", "fp-3", null, null);
        assertThat(decide(scenario, scenario.leader, onHeadTwo, ReviewDecision.APPROVE)).isNull();

        // Force-push back to the blocked head. A stored flag cleared on head change
        // would leave this open; deriving the block from the rows re-locks it.
        scenario.moveTo("head-1", "fp-2");
        assertThat(decide(scenario, scenario.reviewer, alsoOnHeadOne, ReviewDecision.APPROVE))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(decisionOf(alsoOnHeadOne)).isEqualTo("PENDING");
    }

    @Test
    void neitherTheBaseNorTheRequirementNorAReSyncLiftsTheBlock() {
        Scenario scenario = new Scenario();
        long blocking = scenario.completedReview("head-1", "fp-1", null, null);
        assertThat(decide(scenario, scenario.reviewer, blocking, ReviewDecision.REQUEST_CHANGES)).isNull();

        // The base branch moved: same head, new diff fingerprint, new review identity.
        long afterBaseMoved = scenario.completedReview("head-1", "fp-2", null, null);
        scenario.moveTo("head-1", "fp-2");
        assertThat(decide(scenario, scenario.leader, afterBaseMoved, ReviewDecision.APPROVE))
                .isEqualTo(HttpStatus.CONFLICT);

        // The pull request was pointed at a requirement.
        long requirement = scenario.requirement();
        long firstRevision = scenario.currentRevisionOf(requirement);
        scenario.link(requirement);
        long afterAssociation = scenario.completedReview("head-1", "fp-2", requirement, firstRevision);
        assertThat(decide(scenario, scenario.leader, afterAssociation, ReviewDecision.APPROVE))
                .isEqualTo(HttpStatus.CONFLICT);

        // A new requirement revision was published.
        long secondRevision = scenario.publishRevision(requirement);
        long afterRevision = scenario.completedReview("head-1", "fp-2", requirement, secondRevision);
        assertThat(decide(scenario, scenario.leader, afterRevision, ReviewDecision.APPROVE))
                .isEqualTo(HttpStatus.CONFLICT);

        // The diff was re-synced, again without the head moving.
        long afterResync = scenario.completedReview("head-1", "fp-3", requirement, secondRevision);
        scenario.moveTo("head-1", "fp-3");
        assertThat(decide(scenario, scenario.leader, afterResync, ReviewDecision.APPROVE))
                .isEqualTo(HttpStatus.CONFLICT);

        // And the control: a genuinely new head is decidable, so the four refusals
        // above are the gate at work rather than a gate that never opens.
        scenario.moveTo("head-2", "fp-4");
        long onNewHead = scenario.completedReview("head-2", "fp-4", requirement, secondRevision);
        assertThat(decide(scenario, scenario.leader, onNewHead, ReviewDecision.APPROVE)).isNull();
    }

    // -------------------------------------------------------------- role matrix

    @Test
    void aDeveloperCannotDecide() {
        Scenario scenario = new Scenario();
        long review = scenario.completedReview("head-1", "fp-1", null, null);

        // A member, so 403 rather than 404: they already know the project exists.
        assertThat(decide(scenario, scenario.developer, review, ReviewDecision.APPROVE))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(decide(scenario, scenario.developer, review, ReviewDecision.REQUEST_CHANGES))
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Somebody outside the project gets the answer a missing project gets.
        long stranger = account("stranger");
        assertThat(decide(scenario, stranger, review, ReviewDecision.APPROVE))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(decisionOf(review)).isEqualTo("PENDING");
    }

    @Test
    void anotherProjectsReviewIsInvisible() {
        Scenario theirs = new Scenario();
        long foreign = theirs.completedReview("head-1", "fp-1", null, null);
        Scenario mine = new Scenario();

        // Same answer for their review and for an id that was never issued.
        assertThat(statusOf(() -> decisions.detail(mine.projectId, mine.leader, foreign)))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(() -> decisions.detail(mine.projectId, mine.leader, foreign + 9_000)))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(() -> decisions.decide(mine.projectId, mine.leader, foreign,
                ReviewDecision.APPROVE, null))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --------------------------------------------------------------- read paths

    @Test
    void theDetailDerivesCurrencyAndTheHistoryKeepsEveryRound() {
        Scenario scenario = new Scenario();
        long first = scenario.completedReview("head-1", "fp-1", null, null);
        scenario.moveTo("head-2", "fp-2");
        long second = scenario.completedReview("head-2", "fp-2", null, null);

        assertThat(decisions.detail(scenario.projectId, scenario.developer, first).isCurrent()).isFalse();
        assertThat(decisions.detail(scenario.projectId, scenario.developer, second).isCurrent()).isTrue();

        // Old reviews are never removed or overwritten, and the order is deterministic.
        assertThat(decisions.history(scenario.projectId, scenario.developer, scenario.pullRequestId))
                .extracting(ReviewViews.ReviewSummary::id).containsExactly(first, second);
    }

    // --------------------------------------------------------------------- http

    @Test
    void theDecisionEndpointIsRoutedAndRoleChecked() throws Exception {
        Scenario scenario = new Scenario();
        long review = scenario.completedReview("head-1", "fp-1", null, null);
        String path = "/api/projects/" + scenario.projectId + "/reviews/" + review + "/decision";

        mockMvc.perform(MockMvcRequestBuilders.post(path)
                        .with(user(usernameOf(scenario.developer))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"APPROVE\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(MockMvcRequestBuilders.post(path)
                        .with(user(usernameOf(scenario.leader))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"APPROVE\", \"comment\": \"Ship it.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVE"))
                .andExpect(jsonPath("$.decisionAt").isNotEmpty());
        assertThat(jdbc.queryForObject("select decision_by from review where id = ?", Long.class, review))
                .isEqualTo(scenario.leader);

        mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/projects/" + scenario.projectId + "/reviews/" + review)
                        .with(user(usernameOf(scenario.reviewer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCurrent").value(true))
                .andExpect(jsonPath("$.decisionComment").value("Ship it."));
    }

    // ---------------------------------------------------------------- machinery

    /**
     * The gate of research 5.4 with the pull request lock left out, run on its own
     * connection and rolled back. Its affected-row count is the size of the hole.
     */
    private int gateWithoutTheRowLock(Scenario scenario, long reviewId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement gate = connection.prepareStatement("""
                    update review r
                       set decision = 'APPROVE', decision_by = ?, decision_at = now()
                      from pull_request p
                     where r.project_id = ? and r.id = ?
                       and p.project_id = r.project_id and p.id = r.pull_request_id
                       and r.status = 'COMPLETED'
                       and r.decision = 'PENDING'
                       and r.head_sha = p.head_sha
                       and r.review_input_fingerprint = p.review_input_fingerprint
                       and r.requirement_revision_id is not distinct from
                           (select req.current_revision_id from requirement req
                             where req.project_id = p.project_id and req.id = p.requirement_id)
                       and not exists (select 1 from review blocking
                                        where blocking.project_id = r.project_id
                                          and blocking.pull_request_id = r.pull_request_id
                                          and blocking.head_sha = p.head_sha
                                          and blocking.decision = 'REQUEST_CHANGES')
                    """)) {
                gate.setLong(1, scenario.leader);
                gate.setLong(2, scenario.projectId);
                gate.setLong(3, reviewId);
                int affected = gate.executeUpdate();
                connection.rollback();
                return affected;
            }
        }
    }

    /** Whether some session is blocked on a lock while taking the pull request row. */
    private boolean waitsForThePullRequestRow() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                     where wait_event_type = 'Lock' and query ilike '%pull_request%for update%'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private <T> List<T> race(Callable<T> one, Callable<T> two) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<T> first = pool.submit(gatedBy(start, one));
            Future<T> second = pool.submit(gatedBy(start, two));
            start.countDown();
            // Arrays.asList, not List.of: the winner's outcome is null.
            return Arrays.asList(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> Callable<T> gatedBy(CountDownLatch start, Callable<T> action) {
        return () -> {
            start.await();
            return action.call();
        };
    }

    private HttpStatus decide(Scenario scenario, long actorId, long reviewId, ReviewDecision decision) {
        return statusOf(() -> decisions.decide(scenario.projectId, actorId, reviewId, decision, null));
    }

    /** The status the API would answer with, or null when the call was allowed to succeed. */
    private static HttpStatus statusOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (ApiException exception) {
            return exception.getStatus();
        }
    }

    private String decisionOf(long reviewId) {
        return jdbc.queryForObject("select decision from review where id = ?", String.class, reviewId);
    }

    private long account(String role) {
        return jdbc.queryForObject("insert into user_account (username, display_name, password_hash) "
                + "values (?, 'Test User', 'bcrypt-placeholder') returning id", Long.class,
                "decision-" + role + "-" + SEQUENCE.incrementAndGet());
    }

    private String usernameOf(long userId) {
        return jdbc.queryForObject("select username from user_account where id = ?", String.class, userId);
    }

    /**
     * One project with all three roles, an SCM repository and a pull request on
     * {@code head-1}. Rows are inserted directly: the point of these tests is what
     * the decision path does with a given database state, and building that state
     * through other slices' endpoints would couple them to work happening in
     * parallel.
     */
    private final class Scenario {

        private final long leader = account("leader");
        private final long developer = account("developer");
        private final long reviewer = account("reviewer");
        private final long projectId;
        private final long pullRequestId;

        private Scenario() {
            int ordinal = SEQUENCE.incrementAndGet();
            projectId = jdbc.queryForObject("insert into project (name, created_by, status) "
                    + "values (?, ?, 'ACTIVE') returning id", Long.class, "decision-" + ordinal, leader);
            member(leader, "LEADER");
            member(developer, "DEVELOPER");
            member(reviewer, "REVIEWER");
            // The stable identity triple is unique across every project, so this id
            // has to be unique across the whole test run, not just this class.
            long repositoryId = jdbc.queryForObject("insert into scm_repository (project_id, provider, "
                    + "instance_identity, external_id, api_base, encrypted_token, encrypted_secret) "
                    + "values (?, 'GITHUB', 'github.com', ?, 'https://api.github.com', 'x', 'y') "
                    + "returning id", Long.class, projectId, "repo-" + UUID.randomUUID());
            pullRequestId = jdbc.queryForObject("insert into pull_request (project_id, repository_id, "
                    + "external_number, base_sha, head_sha, review_input_fingerprint, changed_files, "
                    + "author_external_user_id, author_username) "
                    + "values (?, ?, 1, 'base-1', 'head-1', 'fp-1', '[]'::jsonb, 'gh-1', 'octocat') "
                    + "returning id", Long.class, projectId, repositoryId);
        }

        private void member(long userId, String role) {
            jdbc.update("with member as (insert into project_member (project_id, user_id) values (?, ?) returning project_id, user_id) insert into project_member_role (project_id, user_id, role) select project_id, user_id, ? from member",
                    projectId, userId, role);
        }

        private void moveTo(String headSha, String fingerprint) {
            jdbc.update("update pull_request set head_sha = ?, review_input_fingerprint = ? where id = ?",
                    headSha, fingerprint, pullRequestId);
        }

        private void link(Long requirementId) {
            jdbc.update("update pull_request set requirement_id = ? where id = ?",
                    requirementId, pullRequestId);
        }

        private long requirement() {
            long requirementId = jdbc.queryForObject("insert into requirement (project_id, status) "
                    + "values (?, 'READY') returning id", Long.class, projectId);
            publishRevision(requirementId);
            return requirementId;
        }

        private long publishRevision(long requirementId) {
            Integer previous = jdbc.queryForObject(
                    "select coalesce(max(seq), 0) from requirement_revision where requirement_id = ?",
                    Integer.class, requirementId);
            long revisionId = jdbc.queryForObject("insert into requirement_revision (project_id, "
                    + "requirement_id, seq, title, created_by) values (?, ?, ?, ?, ?) returning id",
                    Long.class, projectId, requirementId, previous + 1, "Revision " + (previous + 1), leader);
            jdbc.update("update requirement set current_revision_id = ? where id = ?",
                    revisionId, requirementId);
            return revisionId;
        }

        private long currentRevisionOf(long requirementId) {
            return jdbc.queryForObject("select current_revision_id from requirement where id = ?",
                    Long.class, requirementId);
        }

        private long completedReview(String headSha, String fingerprint, Long requirementId,
                Long revisionId) {
            return review(headSha, fingerprint, requirementId, revisionId, "COMPLETED");
        }

        private long review(String headSha, String fingerprint, Long requirementId, Long revisionId,
                String status) {
            return jdbc.queryForObject("insert into review (project_id, pull_request_id, head_sha, "
                    + "review_input_fingerprint, requirement_id, requirement_revision_id, status, "
                    + "execution_attempt) values (?, ?, ?, ?, ?, ?, ?, 1) returning id", Long.class,
                    projectId, pullRequestId, headSha, fingerprint, requirementId, revisionId, status);
        }
    }
}
