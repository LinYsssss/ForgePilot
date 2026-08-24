package com.forgepilot.scm;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Human correction of the pull request to requirement association (PRD P1) and
 * the audit D007 requires of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PullRequestAssociationTest extends ScmTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private PullRequestAssociationService associations;

    @Autowired
    private ScmRepositoryService repositories;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void aCorrectionMovesTheLinkAndWritesExactlyOneUserAuditRow() {
        Fixture fixture = new Fixture();
        long from = fixture.requirement();
        long to = fixture.requirement();
        long pullRequest = fixture.pullRequest(from);

        PullRequestResponse corrected = associations.correct(fixture.project, fixture.leader,
                pullRequest, to, "The branch named the wrong requirement.");

        assertThat(corrected.requirementId()).isEqualTo(to);
        assertThat(storedRequirementOf(pullRequest)).isEqualTo(to);

        assertThat(auditOf(pullRequest)).singleElement().satisfies(row -> {
            assertThat(row.get("actor_type")).isEqualTo("USER");
            assertThat(row.get("actor_user_id")).isEqualTo(fixture.leader);
            assertThat(row.get("from_requirement_id")).isEqualTo(from);
            assertThat(row.get("to_requirement_id")).isEqualTo(to);
            assertThat(row.get("reason")).isEqualTo("The branch named the wrong requirement.");
        });
    }

    /**
     * D007 asks for the audit row and the change to be one transaction, so this
     * rolls the surrounding transaction back and requires <em>both</em> to be
     * gone. A change committed on its own — in its own transaction, or flushed
     * before the audit row — would survive this and leave an unexplained
     * association behind.
     */
    @Test
    void theChangeAndItsAuditRowCommitTogetherOrNotAtAll() {
        Fixture fixture = new Fixture();
        long from = fixture.requirement();
        long to = fixture.requirement();
        long pullRequest = fixture.pullRequest(from);

        TransactionTemplate outer = new TransactionTemplate(transactions);
        try {
            outer.executeWithoutResult(status -> {
                associations.correct(fixture.project, fixture.leader, pullRequest, to, "Corrected.");
                throw new IllegalStateException("abandon this transaction");
            });
        } catch (IllegalStateException abandoned) {
            assertThat(abandoned).hasMessage("abandon this transaction");
        }

        assertThat(storedRequirementOf(pullRequest)).isEqualTo(from);
        assertThat(auditOf(pullRequest)).isEmpty();
    }

    @Test
    void clearingTheLinkIsACorrectionAndIsAuditedTheSameWay() {
        Fixture fixture = new Fixture();
        long from = fixture.requirement();
        long pullRequest = fixture.pullRequest(from);

        PullRequestResponse cleared = associations.correct(fixture.project, fixture.leader,
                pullRequest, null, "This pull request implements nothing tracked.");

        assertThat(cleared.requirementId()).isNull();
        assertThat(storedRequirementOf(pullRequest)).isNull();
        assertThat(auditOf(pullRequest)).singleElement().satisfies(row -> {
            assertThat(row.get("actor_type")).isEqualTo("USER");
            assertThat(row.get("actor_user_id")).isEqualTo(fixture.leader);
            assertThat(row.get("from_requirement_id")).isEqualTo(from);
            assertThat(row.get("to_requirement_id")).isNull();
        });

        // And clearing an already empty link is not a change, so the audit table's
        // CHECK refuses it rather than recording a no-op.
        assertThat(statusOf(() -> associations.correct(fixture.project, fixture.leader, pullRequest,
                null, "Again."))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(auditOf(pullRequest)).hasSize(1);
    }

    @Test
    void aRequirementFromAnotherProjectIsRefusedAndIndistinguishableFromOneThatNeverExisted() {
        Fixture fixture = new Fixture();
        Fixture other = new Fixture();
        long linked = fixture.requirement();
        long foreign = other.requirement();
        long pullRequest = fixture.pullRequest(linked);

        assertThat(statusOf(() -> associations.correct(fixture.project, fixture.leader, pullRequest,
                foreign, "Theirs."))).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // An id belonging to another project and an id that was never issued answer
        // identically, message included, so the refusal confirms nothing about what
        // the other project contains.
        assertThat(refusalOf(() -> associations.correct(fixture.project, fixture.leader, pullRequest,
                foreign, "Theirs.")))
                .isEqualTo(refusalOf(() -> associations.correct(fixture.project, fixture.leader,
                        pullRequest, foreign + 9_000, "Never issued.")));

        // Refused before anything is written, not stored and rolled back afterwards.
        assertThat(storedRequirementOf(pullRequest)).isEqualTo(linked);
        assertThat(auditOf(pullRequest)).isEmpty();
    }

    @Test
    void aLeaderMayAlwaysCorrectAndAReviewerNeverMay() {
        Fixture fixture = new Fixture();
        Fixture stranger = new Fixture();
        long linked = fixture.requirement();
        long target = fixture.requirement();
        long pullRequest = fixture.pullRequest(linked);

        // A REVIEWER decides reviews; which requirement a pull request implements is
        // not a review conclusion, so PRD P1 gives them no path here at all.
        long reviewer = fixture.member(ProjectRole.REVIEWER, "gh-reviewer");
        assertThat(statusOf(() -> associations.correct(fixture.project, reviewer, pullRequest,
                target, "Mine now."))).isEqualTo(HttpStatus.FORBIDDEN);

        // A DEVELOPER who is not the author is refused the same way, and a member
        // with no verified SCM identity at all matches nothing (fail closed).
        long otherDeveloper = fixture.member(ProjectRole.DEVELOPER, "gh-somebody-else");
        long unverified = fixture.member(ProjectRole.DEVELOPER, null);
        for (long member : List.of(otherDeveloper, unverified)) {
            assertThat(statusOf(() -> associations.correct(fixture.project, member, pullRequest,
                    target, "Mine now."))).isEqualTo(HttpStatus.FORBIDDEN);
        }

        // A non-member gets the same answer as for a project that does not exist.
        assertThat(statusOf(() -> associations.correct(fixture.project, stranger.leader, pullRequest,
                target, "Not mine."))).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(storedRequirementOf(pullRequest)).isEqualTo(linked);
        assertThat(auditOf(pullRequest)).isEmpty();

        assertThat(associations.correct(fixture.project, fixture.leader, pullRequest, target, "Leader.")
                .requirementId()).isEqualTo(target);
    }

    /**
     * PRD P1 / D007 / D016.2, the half that batch 2 deferred and batch 3 owed:
     * the author may correct their own pull request until this head carries a
     * final human decision.
     *
     * <p>The PENDING review is load-bearing. Webhook delivery creates one inside
     * the same transaction that updates the pull request, so a gate written as
     * "no review exists" would close the author's window before they could ever
     * reach it — which is exactly why D007 spells out "即使自动 PENDING 已存在".
     */
    @Test
    void theAuthorMayCorrectTheirOwnPullRequestWhileNoFinalDecisionExistsOnThisHead() {
        Fixture fixture = new Fixture();
        long linked = fixture.requirement();
        long target = fixture.requirement();
        long pullRequest = fixture.pullRequest(linked);
        long author = fixture.member(ProjectRole.DEVELOPER, "gh-1");

        fixture.review(pullRequest, "head-sha", "PENDING", null);

        PullRequestResponse visible = repositories.pullRequest(fixture.project, author, pullRequest);
        assertThat(visible.authorUserId()).isNull();
        assertThat(visible.canEditRequirementAssociation()).isTrue();

        PullRequestResponse corrected = associations.correct(fixture.project, author, pullRequest,
                target, "I named the wrong requirement in the branch.");

        assertThat(corrected.requirementId()).isEqualTo(target);
        assertThat(auditOf(pullRequest)).singleElement()
                .satisfies(row -> assertThat(row.get("actor_user_id")).isEqualTo(author));
    }

    @Test
    void aFinalDecisionOnThisHeadClosesTheAuthorsWindowUntilANewHead() {
        Fixture fixture = new Fixture();
        long linked = fixture.requirement();
        long target = fixture.requirement();
        long pullRequest = fixture.pullRequest(linked);
        long author = fixture.member(ProjectRole.DEVELOPER, "gh-1");

        fixture.review(pullRequest, "head-sha", "COMPLETED", "REQUEST_CHANGES");

        assertThat(repositories.pullRequest(fixture.project, author, pullRequest)
                .canEditRequirementAssociation()).isFalse();

        // The role is right and the person is right; what stops them is a fact about
        // this head, so it is a conflict rather than a forbidden.
        assertThat(statusOf(() -> associations.correct(fixture.project, author, pullRequest, target,
                "Too late."))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(storedRequirementOf(pullRequest)).isEqualTo(linked);
        assertThat(auditOf(pullRequest)).isEmpty();

        // The LEADER is never gated by it (PRD P1: LEADER 始终可改).
        assertThat(associations.correct(fixture.project, fixture.leader, pullRequest, target, "Leader.")
                .requirementId()).isEqualTo(target);

        // Pushing a new commit re-opens the author's window: the gate is derived per
        // head every time, never cached on the pull request row.
        jdbc.update("update pull_request set head_sha = 'head-two' where id = ?", pullRequest);
        assertThat(associations.correct(fixture.project, author, pullRequest, linked, "New head.")
                .requirementId()).isEqualTo(linked);
    }

    /**
     * D010: "this is my pull request" is decided by the stable external user id.
     * A member whose SCM username matches the author's while their external id
     * does not is a different person — usernames are reassignable.
     */
    @Test
    void authorshipIsDecidedByExternalIdAndNeverByUsername() {
        Fixture fixture = new Fixture();
        long linked = fixture.requirement();
        long target = fixture.requirement();
        long pullRequest = fixture.pullRequest(linked);

        long impostor = fixture.member(ProjectRole.DEVELOPER, "gh-2");

        assertThat(statusOf(() -> associations.correct(fixture.project, impostor, pullRequest, target,
                "Same name."))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(storedRequirementOf(pullRequest)).isEqualTo(linked);
        assertThat(auditOf(pullRequest)).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private Long storedRequirementOf(long pullRequestId) {
        return jdbc.queryForObject("select requirement_id from pull_request where id = ?",
                Long.class, pullRequestId);
    }

    private List<Map<String, Object>> auditOf(long pullRequestId) {
        return jdbc.queryForList("select actor_type, actor_user_id, from_requirement_id, "
                + "to_requirement_id, reason from pull_request_requirement_event "
                + "where pull_request_id = ? order by id", pullRequestId);
    }

    /** The status the API would return, or null when the call was allowed to succeed. */
    private static HttpStatus statusOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (ApiException expected) {
            return expected.getStatus();
        } catch (DataIntegrityViolationException refusedByTheDatabase) {
            // ApiExceptionHandler maps this to 409 (D013.11: never caught in a service).
            return HttpStatus.CONFLICT;
        }
    }

    /** Everything the caller can tell two refusals apart by: the status and the message. */
    private static String refusalOf(Runnable action) {
        try {
            action.run();
            return "allowed";
        } catch (ApiException expected) {
            return expected.getStatus() + "|" + expected.getMessage();
        }
    }

    /** One project with a LEADER, a connected repository, and rows built straight through SQL. */
    private final class Fixture {

        private final long leader;
        private final long project;
        private final long repository;

        private Fixture() {
            this.leader = account();
            this.project = jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "assoc-" + SEQUENCE.incrementAndGet(), leader);
            jdbc.update("with member as (insert into project_member (project_id, user_id) values (?, ?) returning project_id, user_id) insert into project_member_role (project_id, user_id, role) select project_id, user_id, 'LEADER' from member",
                    project, leader);
            this.repository = jdbc.queryForObject(
                    "insert into scm_repository (project_id, provider, instance_identity, external_id, "
                            + "api_base, encrypted_token, encrypted_secret) values (?, 'GITHUB', "
                            + "?, ?, 'http://127.0.0.1:34567', 'x', 'y') returning id",
                    Long.class, project, "assoc-host-" + SEQUENCE.incrementAndGet(),
                    "assoc-repo-" + SEQUENCE.incrementAndGet());
        }

        private long requirement() {
            long requirementId = jdbc.queryForObject(
                    "insert into requirement (project_id, status) values (?, 'DRAFT') returning id",
                    Long.class, project);
            long revision = jdbc.queryForObject(
                    "insert into requirement_revision (project_id, requirement_id, seq, title, created_by) "
                            + "values (?, ?, 1, 'A requirement', ?) returning id",
                    Long.class, project, requirementId, leader);
            jdbc.update("update requirement set current_revision_id = ? where id = ?",
                    revision, requirementId);
            return requirementId;
        }

        private long pullRequest(Long requirementId) {
            return jdbc.queryForObject("insert into pull_request (project_id, repository_id, "
                    + "external_number, base_sha, head_sha, review_input_fingerprint, changed_files, "
                    + "requirement_id, author_external_user_id, author_username) values (?, ?, ?, "
                    + "'base-sha', 'head-sha', 'fingerprint', '[]'::jsonb, ?, 'gh-1', 'octocat') "
                    + "returning id", Long.class, project, repository,
                    SEQUENCE.incrementAndGet(), requirementId);
        }

        private long account() {
            return jdbc.queryForObject(
                    "insert into user_account (username, display_name, password_hash) values (?, 'Test User', 'x') returning id",
                    Long.class, "assoc-user-" + SEQUENCE.incrementAndGet());
        }

        /**
         * A verified SCM identity is what "this is my pull request" is decided by
         * (D010). Passing null leaves the member unverified, which must match
         * nothing rather than everything.
         */
        private long member(ProjectRole role, String scmExternalUserId) {
            long user = account();
            jdbc.update("with member as (insert into project_member (project_id, user_id) "
                            + "values (?, ?) returning project_id, user_id) "
                            + "insert into project_member_role (project_id, user_id, role) "
                            + "select project_id, user_id, ? from member",
                    project, user, role.name());
            if (scmExternalUserId != null) {
                long identity = jdbc.queryForObject("insert into scm_identity (user_id, provider, "
                                + "instance_identity, external_user_id, external_username, label, usage_type, "
                                + "verification_status, verification_method, verified_at, last_synced_at) "
                                + "select ?, provider, instance_identity, ?, 'octocat', 'Work', 'WORK', "
                                + "'VERIFIED', 'ONE_TIME_TOKEN', now(), now() from scm_repository where id = ? "
                                + "returning id",
                        Long.class, user, scmExternalUserId, repository);
                jdbc.update("insert into project_member_scm_binding (project_id, user_id, scm_identity_id, "
                                + "repository_id, status, requested_by, approved_by, decided_at, activated_at) "
                                + "values (?, ?, ?, ?, 'ACTIVE', ?, ?, now(), now())",
                        project, user, identity, repository, user, user);
            }
            return user;
        }

        /**
         * A review row written straight through SQL, because the point is the state
         * the gate reads, not how the engine got there. The CHECK constraints refuse
         * an inconsistent one: a non-PENDING decision needs COMPLETED plus an actor
         * and a timestamp, so this fixture cannot fake a decision that the engine
         * itself could never have produced.
         */
        private void review(long pullRequestId, String headSha, String status, String decision) {
            boolean isFinal = decision != null;
            jdbc.update("insert into review (project_id, pull_request_id, head_sha, "
                    + "review_input_fingerprint, status, decision, decision_by, decision_at) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                    project, pullRequestId, headSha,
                    "fingerprint-" + SEQUENCE.incrementAndGet(), status,
                    isFinal ? decision : "PENDING",
                    isFinal ? leader : null,
                    isFinal ? Timestamp.from(Instant.now()) : null);
        }
    }
}
