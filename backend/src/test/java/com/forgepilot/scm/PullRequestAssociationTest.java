package com.forgepilot.scm;

import static org.assertj.core.api.Assertions.assertThat;

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
    void onlyALeaderMayCorrectTheAssociation() {
        Fixture fixture = new Fixture();
        Fixture stranger = new Fixture();
        long linked = fixture.requirement();
        long target = fixture.requirement();
        long pullRequest = fixture.pullRequest(linked);

        for (ProjectRole role : List.of(ProjectRole.DEVELOPER, ProjectRole.REVIEWER)) {
            long member = fixture.member(role);
            // A member knows the project exists, so 403 tells them nothing new.
            assertThat(statusOf(() -> associations.correct(fixture.project, member, pullRequest,
                    target, "Mine now."))).as("correct as %s", role).isEqualTo(HttpStatus.FORBIDDEN);
        }

        // A non-member gets the same answer as for a project that does not exist.
        assertThat(statusOf(() -> associations.correct(fixture.project, stranger.leader, pullRequest,
                target, "Not mine."))).isEqualTo(HttpStatus.NOT_FOUND);

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
            jdbc.update("insert into project_member (project_id, user_id, role) values (?, ?, 'LEADER')",
                    project, leader);
            this.repository = jdbc.queryForObject(
                    "insert into scm_repository (project_id, provider, instance_identity, external_id, "
                            + "api_base, encrypted_token, encrypted_secret) values (?, 'GITHUB', "
                            + "'127.0.0.1:34567', ?, 'http://127.0.0.1:34567', 'x', 'y') returning id",
                    Long.class, project, "assoc-repo-" + SEQUENCE.incrementAndGet());
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
                    "insert into user_account (username, password_hash) values (?, 'x') returning id",
                    Long.class, "assoc-user-" + SEQUENCE.incrementAndGet());
        }

        private long member(ProjectRole role) {
            long user = account();
            jdbc.update("insert into project_member (project_id, user_id, role) values (?, ?, ?)",
                    project, user, role.name());
            return user;
        }
    }
}
