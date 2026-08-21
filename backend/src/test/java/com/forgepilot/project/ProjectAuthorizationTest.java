package com.forgepilot.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Project isolation, the role matrix and the LEADER invariant, exercised through
 * the services that own them. Cross-project reads must be indistinguishable from
 * a missing resource; role failures inside a project may say 403 because the
 * caller already knows the project exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProjectAuthorizationTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private ProjectService projects;

    @Autowired
    private ProjectMemberService members;

    @Autowired
    private ProjectMemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ creation

    @Test
    void creatorBecomesLeaderInTheSameTransaction() {
        long creator = account();

        ProjectResponse project = projects.create("Alpha", creator);

        assertThat(project.myRole()).isEqualTo(ProjectRole.LEADER);
        assertThat(project.status()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(memberRepository.findByProjectIdAndUserId(project.id(), creator))
                .get().extracting(ProjectMember::getRole).isEqualTo(ProjectRole.LEADER);
        assertThat(leaderCount(project.id())).isEqualTo(1);
    }

    @Test
    void listOnlyShowsProjectsTheUserBelongsTo() {
        long mine = account();
        long stranger = account();
        long visible = projects.create("Visible", mine).id();
        projects.create("Hidden", stranger);

        assertThat(projects.listForUser(mine)).extracting(ProjectResponse::id).containsExactly(visible);
    }

    // ----------------------------------------------------------------- isolation

    @Test
    void anotherProjectsIdIsIndistinguishableFromOneThatDoesNotExist() {
        long outsider = account();
        long owner = account();
        long foreign = projects.create("Foreign", owner).id();
        projects.create("Own", outsider);

        long missing = foreign + 100_000L;

        assertThat(statusOf(() -> projects.get(foreign, outsider))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(() -> projects.get(missing, outsider))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(() -> members.list(foreign, outsider))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(() -> members.list(missing, outsider))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(() -> members.add(foreign, outsider, "whoever", ProjectRole.DEVELOPER)))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(() -> members.update(foreign, outsider, owner, ProjectRole.DEVELOPER, null, null)))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------- role matrix

    @Test
    void onlyLeaderMayManageMembers() {
        long leader = account();
        long developer = account();
        long reviewer = account();
        long project = projects.create("Roles", leader).id();
        String developerName = usernameOf(developer);
        String reviewerName = usernameOf(reviewer);
        members.add(project, leader, developerName, ProjectRole.DEVELOPER);
        members.add(project, leader, reviewerName, ProjectRole.REVIEWER);

        for (long actor : List.of(developer, reviewer)) {
            assertThat(statusOf(() -> members.add(project, actor, reviewerName, ProjectRole.DEVELOPER)))
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(statusOf(() -> members.update(project, actor, developer, ProjectRole.REVIEWER, null, null)))
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(statusOf(() -> members.update(project, actor, developer, null, "gh-x", "octocat")))
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        // Reading is open to every member.
        assertThat(members.list(project, developer)).hasSize(3);
    }

    // ------------------------------------------------------------ leader invariant

    @Test
    void aSecondLeaderIsRejectedByTheDatabase() {
        long leader = account();
        long other = account();
        long project = projects.create("Single", leader).id();

        assertThatThrownBy(() -> members.add(project, leader, usernameOf(other), ProjectRole.LEADER))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(leaderCount(project)).isEqualTo(1);
    }

    @Test
    void transferDemotesBeforeItPromotesAndKeepsExactlyOneLeader() {
        long leader = account();
        long successor = account();
        long project = projects.create("Transfer", leader).id();
        members.add(project, leader, usernameOf(successor), ProjectRole.DEVELOPER);

        MemberResponse promoted = members.update(project, leader, successor, ProjectRole.LEADER, null, null);

        assertThat(promoted.role()).isEqualTo(ProjectRole.LEADER);
        assertThat(leaderCount(project)).isEqualTo(1);
        assertThat(memberRepository.findByProjectIdAndUserId(project, leader))
                .get().extracting(ProjectMember::getRole).isEqualTo(ProjectRole.DEVELOPER);
    }

    @Test
    void theOnlyLeaderCannotBeDemoted() {
        long leader = account();
        long project = projects.create("Locked", leader).id();

        assertThat(statusOf(() -> members.update(project, leader, leader, ProjectRole.DEVELOPER, null, null)))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(leaderCount(project)).isEqualTo(1);
    }

    @Test
    void concurrentTransfersLeaveExactlyOneLeaderAndOnlyOneWinner() throws Exception {
        long leader = account();
        long first = account();
        long second = account();
        long project = projects.create("Race", leader).id();
        members.add(project, leader, usernameOf(first), ProjectRole.DEVELOPER);
        members.add(project, leader, usernameOf(second), ProjectRole.DEVELOPER);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HttpStatus> one = pool.submit(transfer(start, project, leader, first));
            Future<HttpStatus> two = pool.submit(transfer(start, project, leader, second));
            start.countDown();

            List<HttpStatus> outcomes = java.util.Arrays.asList(
                    one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));

            // The loser re-reads its own role only after the winner commits, and by
            // then it is no longer LEADER.
            assertThat(outcomes).containsExactlyInAnyOrder(null, HttpStatus.FORBIDDEN);
            assertThat(leaderCount(project)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // --------------------------------------------------------------- scm identity

    @Test
    void scmIdentityIsUniquePerProjectAndSetOnlyByLeader() {
        long leader = account();
        long developer = account();
        long project = projects.create("Scm", leader).id();
        long otherProject = projects.create("ScmElsewhere", leader).id();
        members.add(project, leader, usernameOf(developer), ProjectRole.DEVELOPER);

        MemberResponse configured = members.update(project, leader, developer, null, "gh-42", "octocat");
        assertThat(configured.scmExternalUserId()).isEqualTo("gh-42");
        assertThat(configured.scmIdentityVerifiedAt()).isNotNull();

        assertThatThrownBy(() -> members.update(project, leader, leader, null, "gh-42", "octocat"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The same SCM account may be used in a different project.
        members.update(otherProject, leader, leader, null, "gh-42", "octocat");
    }

    @Test
    void scmIdentityNeedsBothColumns() {
        long leader = account();
        long project = projects.create("HalfIdentity", leader).id();

        assertThat(statusOf(() -> members.update(project, leader, leader, null, "gh-1", null)))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(statusOf(() -> members.update(project, leader, leader, null, null, "octocat")))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ------------------------------------------------------------------- helpers

    private Callable<HttpStatus> transfer(CountDownLatch start, long project, long actor, long target) {
        return () -> {
            start.await();
            return statusOf(() -> members.update(project, actor, target, ProjectRole.LEADER, null, null));
        };
    }

    private long account() {
        String username = "member-" + SEQUENCE.incrementAndGet();
        Long id = jdbc.queryForObject(
                "insert into user_account (username, password_hash) values (?, 'bcrypt-placeholder') "
                        + "returning id", Long.class, username);
        assertThat(id).isNotNull();
        return id;
    }

    private String usernameOf(long userId) {
        return jdbc.queryForObject("select username from user_account where id = ?", String.class, userId);
    }

    private int leaderCount(long projectId) {
        Integer value = jdbc.queryForObject(
                "select count(*) from project_member where project_id = ? and role = 'LEADER'",
                Integer.class, projectId);
        return value == null ? 0 : value;
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
}
