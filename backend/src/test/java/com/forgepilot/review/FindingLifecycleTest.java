package com.forgepilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectRole;
import com.forgepilot.review.FindingLifecycleService.Move;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The Finding's human lifecycle: which transitions exist, who may take each one,
 * what reopening requires, and what the audit trail says afterwards.
 *
 * <p>The state machine and the role matrix are both asserted as data — every pair
 * and every cell — rather than by picking a few interesting cases. An
 * authorization table tested by sampling is an authorization table with untested
 * cells, and the two cells where a LEADER is refused are exactly the ones a
 * plausible-looking implementation gets wrong.
 */
@SpringBootTest
class FindingLifecycleTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private FindingLifecycleService lifecycle;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ------------------------------------------------------------ state machine

    /**
     * The service's transition table and {@link FindingStatus}'s must describe the
     * same state machine. A pair that existed in one and not the other would either
     * be reachable without appearing in the documented lifecycle, or documented and
     * unreachable.
     */
    @Test
    void theTransitionTableAndTheStateMachineDescribeTheSameLifecycle() {
        for (FindingStatus from : FindingStatus.values()) {
            Set<FindingStatus> allowedByService = FindingLifecycleService.moves().get(from).keySet();
            Set<FindingStatus> allowedByStateMachine = EnumSet.noneOf(FindingStatus.class);
            for (FindingStatus to : FindingStatus.values()) {
                if (from.canMoveTo(to)) {
                    allowedByStateMachine.add(to);
                }
            }
            assertThat(allowedByService).as("transitions out of %s", from)
                    .containsExactlyInAnyOrderElementsOf(allowedByStateMachine);
        }
    }

    @Test
    void everyLegalPairIsAcceptedAndEveryOtherPairIsRefused() {
        Scenario scenario = new Scenario();

        for (FindingStatus from : FindingStatus.values()) {
            Map<FindingStatus, Move> legal = FindingLifecycleService.moves().get(from);
            for (FindingStatus to : FindingStatus.values()) {
                Move move = legal.get(to);
                // A REJECTED fixture is created as an inherited suppression, because
                // REJECTED -> OPEN is only legal for one; the ordinary rejection is
                // asserted on its own below.
                long finding = from == FindingStatus.REJECTED
                        ? scenario.suppressedFinding()
                        : scenario.finding(from, FindingContinuity.NEW, null);
                long actor = move == null
                        ? scenario.leader
                        : scenario.actorFor(move.allowed().iterator().next());

                HttpStatus outcome = statusOf(() ->
                        lifecycle.move(scenario.projectId, actor, finding, to, null));

                if (move == null) {
                    assertThat(outcome).as("%s -> %s must be refused", from, to)
                            .isEqualTo(HttpStatus.CONFLICT);
                    assertThat(statusOf(finding)).isEqualTo(from.name());
                } else {
                    assertThat(outcome).as("%s -> %s must be allowed", from, to).isNull();
                    assertThat(statusOf(finding)).isEqualTo(to.name());
                }
            }
        }
    }

    // -------------------------------------------------------------- role matrix

    /**
     * PRD.md 3 cell by cell. The two that matter most are
     * {@code CONFIRMED -> IN_PROGRESS} and {@code IN_PROGRESS -> FIXED}, where the
     * LEADER column is ❌: those are the developer's own record of their own work,
     * and widening them would be granting a permission the product withholds.
     */
    @Test
    void theRoleMatrixIsHonouredCellByCell() {
        Scenario scenario = new Scenario();

        for (FindingStatus from : FindingStatus.values()) {
            for (Map.Entry<FindingStatus, Move> entry
                    : FindingLifecycleService.moves().get(from).entrySet()) {
                FindingStatus to = entry.getKey();
                Move move = entry.getValue();
                for (ProjectRole role : ProjectRole.values()) {
                    long finding = from == FindingStatus.REJECTED
                            ? scenario.suppressedFinding()
                            : scenario.finding(from, FindingContinuity.NEW, null);
                    long actor = scenario.actorFor(role);

                    HttpStatus outcome = statusOf(() ->
                            lifecycle.move(scenario.projectId, actor, finding, to, null));

                    if (move.allowed().contains(role)) {
                        assertThat(outcome).as("%s may take %s -> %s", role, from, to).isNull();
                    } else {
                        assertThat(outcome).as("%s may not take %s -> %s", role, from, to)
                                .isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(statusOf(finding)).isEqualTo(from.name());
                    }
                }
            }
        }
    }

    @Test
    void aLeaderCanNeitherClaimNorMarkFixed() {
        Scenario scenario = new Scenario();
        long toClaim = scenario.finding(FindingStatus.CONFIRMED, FindingContinuity.NEW, null);
        long toMarkFixed = scenario.finding(FindingStatus.IN_PROGRESS, FindingContinuity.NEW, null);

        // Stated once more on its own, because it reads like a mistake in the matrix
        // and is not one: PRD.md 3's "Finding 认领、标记已修复" row is ❌ for LEADER.
        assertThat(statusOf(() -> lifecycle.move(scenario.projectId, scenario.leader, toClaim,
                FindingStatus.IN_PROGRESS, null))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(() -> lifecycle.move(scenario.projectId, scenario.leader, toMarkFixed,
                FindingStatus.FIXED, null))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(() -> lifecycle.move(scenario.projectId, scenario.reviewer, toClaim,
                FindingStatus.IN_PROGRESS, null))).isEqualTo(HttpStatus.FORBIDDEN);

        // And the developer can, which is what makes the refusals above meaningful.
        assertThat(statusOf(() -> lifecycle.move(scenario.projectId, scenario.developer, toClaim,
                FindingStatus.IN_PROGRESS, null))).isNull();
        assertThat(statusOf(() -> lifecycle.move(scenario.projectId, scenario.developer, toMarkFixed,
                FindingStatus.FIXED, null))).isNull();
    }

    @Test
    void claimingAssignsTheClaimantAndNobodyElse() {
        Scenario scenario = new Scenario();
        long finding = scenario.finding(FindingStatus.CONFIRMED, FindingContinuity.NEW, null);

        lifecycle.move(scenario.projectId, scenario.developer, finding, FindingStatus.IN_PROGRESS, null);

        assertThat(jdbc.queryForObject("select assignee_id from finding where id = ?", Long.class, finding))
                .isEqualTo(scenario.developer);
    }

    // ------------------------------------------------------------------ reopen

    @Test
    void onlyAnInheritedSuppressionMayBeReopenedAndItKeepsItsLineage() {
        Scenario scenario = new Scenario();
        long ordinary = scenario.finding(FindingStatus.REJECTED, FindingContinuity.NEW, null);
        long suppressed = scenario.suppressedFinding();

        // An ordinary rejection is terminal for every role, LEADER included.
        for (long actor : List.of(scenario.leader, scenario.reviewer, scenario.developer)) {
            assertThat(statusOf(() -> lifecycle.move(scenario.projectId, actor, ordinary,
                    FindingStatus.OPEN, null))).isIn(HttpStatus.CONFLICT, HttpStatus.FORBIDDEN);
        }
        assertThat(statusOf(ordinary)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select count(*) from finding_event where finding_id = ?",
                Integer.class, ordinary)).isZero();

        assertThat(statusOf(() -> lifecycle.move(scenario.projectId, scenario.leader, suppressed,
                FindingStatus.OPEN, null))).isNull();
        assertThat(statusOf(suppressed)).isEqualTo("OPEN");

        // Lineage is a fact about history and does not stop being true because the
        // status changed (PRD.md 5). Clearing it here would erase why the finding
        // was inherited in the first place.
        assertThat(jdbc.queryForObject("select continuity from finding where id = ?", String.class,
                suppressed)).isEqualTo("SUPPRESSED");
    }

    // ------------------------------------------------------------------- audit

    @Test
    void everyMoveWritesExactlyOneEventRecordingTheStatusItLeft() {
        Scenario scenario = new Scenario();
        long finding = scenario.finding(FindingStatus.OPEN, FindingContinuity.NEW, null);

        lifecycle.move(scenario.projectId, scenario.leader, finding, FindingStatus.CONFIRMED, "Real.");
        lifecycle.move(scenario.projectId, scenario.developer, finding, FindingStatus.IN_PROGRESS, null);
        lifecycle.move(scenario.projectId, scenario.developer, finding, FindingStatus.FIXED, "Patched.");
        lifecycle.move(scenario.projectId, scenario.reviewer, finding, FindingStatus.IN_PROGRESS, "Not yet.");
        lifecycle.move(scenario.projectId, scenario.developer, finding, FindingStatus.FIXED, null);
        lifecycle.move(scenario.projectId, scenario.reviewer, finding, FindingStatus.VERIFIED, null);
        lifecycle.move(scenario.projectId, scenario.leader, finding, FindingStatus.CLOSED, null);

        List<ReviewViews.FindingEventView> trail =
                lifecycle.history(scenario.projectId, scenario.developer, finding);

        assertThat(trail).extracting(ReviewViews.FindingEventView::action).containsExactly(
                FindingAction.CONFIRM, FindingAction.CLAIM, FindingAction.MARK_FIXED,
                FindingAction.SEND_BACK, FindingAction.MARK_FIXED, FindingAction.VERIFY,
                FindingAction.CLOSE);
        assertThat(trail).extracting(ReviewViews.FindingEventView::fromStatus).containsExactly(
                FindingStatus.OPEN, FindingStatus.CONFIRMED, FindingStatus.IN_PROGRESS,
                FindingStatus.FIXED, FindingStatus.IN_PROGRESS, FindingStatus.FIXED,
                FindingStatus.VERIFIED);
        assertThat(trail).extracting(ReviewViews.FindingEventView::toStatus).containsExactly(
                FindingStatus.CONFIRMED, FindingStatus.IN_PROGRESS, FindingStatus.FIXED,
                FindingStatus.IN_PROGRESS, FindingStatus.FIXED, FindingStatus.VERIFIED,
                FindingStatus.CLOSED);
        assertThat(trail).extracting(ReviewViews.FindingEventView::actorId).containsExactly(
                scenario.leader, scenario.developer, scenario.developer, scenario.reviewer,
                scenario.developer, scenario.reviewer, scenario.leader);
        assertThat(trail.get(0).comment()).isEqualTo("Real.");

        // A refused move writes nothing at all.
        statusOf(() -> lifecycle.move(scenario.projectId, scenario.leader, finding,
                FindingStatus.OPEN, null));
        assertThat(lifecycle.history(scenario.projectId, scenario.developer, finding)).hasSize(7);
    }

    // ------------------------------------------------------------- concurrency

    @Test
    void twoPeopleActingOnOneOpenFindingProduceOneMoveAndOneEvent() throws Exception {
        Scenario scenario = new Scenario();
        long finding = scenario.finding(FindingStatus.OPEN, FindingContinuity.NEW, null);

        // Both attempt the same move on purpose. Racing CONFIRM against REJECT looks
        // stronger and is weaker: if those two serialise, the second one legally
        // runs CONFIRMED -> REJECTED and both succeed, so the expectation below
        // would depend on the interleaving rather than on the conditional update.
        // Two identical moves have exactly one winner under every interleaving.
        List<HttpStatus> outcomes = race(
                () -> statusOf(() -> lifecycle.move(scenario.projectId, scenario.leader, finding,
                        FindingStatus.CONFIRMED, "Confirming.")),
                () -> statusOf(() -> lifecycle.move(scenario.projectId, scenario.reviewer, finding,
                        FindingStatus.CONFIRMED, "Confirming too.")));

        assertThat(outcomes).containsExactlyInAnyOrder(null, HttpStatus.CONFLICT);

        // Measured without the conditional update: two events, both claiming to
        // start from OPEN, describing a history that never happened.
        List<ReviewViews.FindingEventView> trail =
                lifecycle.history(scenario.projectId, scenario.developer, finding);
        assertThat(trail).hasSize(1);
        assertThat(trail.get(0).fromStatus()).isEqualTo(FindingStatus.OPEN);
        assertThat(trail.get(0).toStatus().name()).isEqualTo(statusOf(finding));
    }

    @Test
    void twoConcurrentReopensOfOneSuppressionLeaveOneWinner() throws Exception {
        Scenario scenario = new Scenario();
        long finding = scenario.suppressedFinding();

        List<HttpStatus> outcomes = race(
                () -> statusOf(() -> lifecycle.move(scenario.projectId, scenario.leader, finding,
                        FindingStatus.OPEN, null)),
                () -> statusOf(() -> lifecycle.move(scenario.projectId, scenario.reviewer, finding,
                        FindingStatus.OPEN, null)));

        assertThat(outcomes).containsExactlyInAnyOrder(null, HttpStatus.CONFLICT);
        assertThat(statusOf(finding)).isEqualTo("OPEN");
        assertThat(lifecycle.history(scenario.projectId, scenario.developer, finding)).hasSize(1);
    }

    // ---------------------------------------------------------------- isolation

    @Test
    void anotherProjectsFindingIsIndistinguishableFromOneThatDoesNotExist() {
        Scenario theirs = new Scenario();
        long foreign = theirs.finding(FindingStatus.OPEN, FindingContinuity.NEW, null);
        Scenario mine = new Scenario();

        assertThat(statusOf(() -> lifecycle.move(mine.projectId, mine.leader, foreign,
                FindingStatus.CONFIRMED, null))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(() -> lifecycle.move(mine.projectId, mine.leader, foreign + 9_000,
                FindingStatus.CONFIRMED, null))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(() -> lifecycle.history(mine.projectId, mine.leader, foreign)))
                .isEqualTo(HttpStatus.NOT_FOUND);

        // And a non-member of the owning project is told the same thing.
        long stranger = account("stranger");
        assertThat(statusOf(() -> lifecycle.move(theirs.projectId, stranger, foreign,
                FindingStatus.CONFIRMED, null))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(foreign)).isEqualTo("OPEN");
    }

    // --------------------------------------------------------------------- http

    @Test
    void theStatusAndEventEndpointsAreRoutedAndRoleChecked() throws Exception {
        Scenario scenario = new Scenario();
        long finding = scenario.finding(FindingStatus.OPEN, FindingContinuity.NEW, null);
        String status = "/api/projects/" + scenario.projectId + "/findings/" + finding + "/status";

        mockMvc.perform(MockMvcRequestBuilders.post(status)
                        .with(user(usernameOf(scenario.developer))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(MockMvcRequestBuilders.post(status)
                        .with(user(usernameOf(scenario.leader))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\", \"comment\": \"Real.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mockMvc.perform(MockMvcRequestBuilders.post(status)
                        .with(user(usernameOf(scenario.leader))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"IN_PROGRESS\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/projects/" + scenario.projectId + "/findings/" + finding + "/events")
                        .with(user(usernameOf(scenario.reviewer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fromStatus").value("OPEN"))
                .andExpect(jsonPath("$[0].toStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$[0].action").value("CONFIRM"));
    }

    // ---------------------------------------------------------------- machinery

    private List<HttpStatus> race(Callable<HttpStatus> one, Callable<HttpStatus> two) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HttpStatus> first = pool.submit(gatedBy(start, one));
            Future<HttpStatus> second = pool.submit(gatedBy(start, two));
            start.countDown();
            // Arrays.asList, not List.of: the winner's outcome is null.
            return Arrays.asList(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<HttpStatus> gatedBy(CountDownLatch start, Callable<HttpStatus> action) {
        return () -> {
            start.await();
            return action.call();
        };
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

    private String statusOf(long findingId) {
        return jdbc.queryForObject("select status from finding where id = ?", String.class, findingId);
    }

    private long account(String role) {
        return jdbc.queryForObject("insert into user_account (username, password_hash) "
                + "values (?, 'bcrypt-placeholder') returning id", Long.class,
                "finding-" + role + "-" + SEQUENCE.incrementAndGet());
    }

    private String usernameOf(long userId) {
        return jdbc.queryForObject("select username from user_account where id = ?", String.class, userId);
    }

    /** One project with all three roles and one completed Review to hang findings off. */
    private final class Scenario {

        private final long leader = account("leader");
        private final long developer = account("developer");
        private final long reviewer = account("reviewer");
        private final long projectId;
        private final long reviewId;

        private Scenario() {
            int ordinal = SEQUENCE.incrementAndGet();
            projectId = jdbc.queryForObject("insert into project (name, created_by, status) "
                    + "values (?, ?, 'ACTIVE') returning id", Long.class, "finding-" + ordinal, leader);
            member(leader, "LEADER");
            member(developer, "DEVELOPER");
            member(reviewer, "REVIEWER");
            long repositoryId = jdbc.queryForObject("insert into scm_repository (project_id, provider, "
                    + "instance_identity, external_id, api_base, encrypted_token, encrypted_secret) "
                    + "values (?, 'GITHUB', 'github.com', ?, 'https://api.github.com', 'x', 'y') "
                    + "returning id", Long.class, projectId, "repo-" + UUID.randomUUID());
            long pullRequestId = jdbc.queryForObject("insert into pull_request (project_id, repository_id, "
                    + "external_number, base_sha, head_sha, review_input_fingerprint, changed_files, "
                    + "author_external_user_id, author_username) "
                    + "values (?, ?, 1, 'base-1', 'head-1', 'fp-1', '[]'::jsonb, 'gh-1', 'octocat') "
                    + "returning id", Long.class, projectId, repositoryId);
            reviewId = jdbc.queryForObject("insert into review (project_id, pull_request_id, head_sha, "
                    + "review_input_fingerprint, status, execution_attempt) "
                    + "values (?, ?, 'head-1', 'fp-1', 'COMPLETED', 1) returning id", Long.class,
                    projectId, pullRequestId);
        }

        private void member(long userId, String role) {
            jdbc.update("insert into project_member (project_id, user_id, role) values (?, ?, ?)",
                    projectId, userId, role);
        }

        private long actorFor(ProjectRole role) {
            return switch (role) {
                case LEADER -> leader;
                case DEVELOPER -> developer;
                case REVIEWER -> reviewer;
            };
        }

        /**
         * A rejection inherited from a previous round: {@code REJECTED} with
         * {@code SUPPRESSED} continuity and a parent it was carried from, which the
         * schema requires of any non-NEW finding.
         */
        private long suppressedFinding() {
            long origin = finding(FindingStatus.REJECTED, FindingContinuity.NEW, null);
            return finding(FindingStatus.REJECTED, FindingContinuity.SUPPRESSED, origin);
        }

        private long finding(FindingStatus status, FindingContinuity continuity, Long carriedFrom) {
            return jdbc.queryForObject("insert into finding (project_id, review_id, review_attempt, "
                    + "finding_type, path, line, evidence, status, finding_key, evidence_hash, "
                    + "basis_hash, continuity, carried_from_finding_id) "
                    + "values (?, ?, 1, 'CODE_QUALITY', 'src/Main.java', 12, 'the evidence', ?, ?, "
                    + "'evidence-hash', 'basis-hash', ?, ?) returning id", Long.class,
                    projectId, reviewId, status.name(), "key-" + SEQUENCE.incrementAndGet(),
                    continuity.name(), carriedFrom);
        }
    }
}
