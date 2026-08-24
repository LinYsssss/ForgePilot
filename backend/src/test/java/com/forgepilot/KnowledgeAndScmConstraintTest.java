package com.forgepilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the batch 2 constraints are enforced by PostgreSQL itself. Every write
 * goes straight through JdbcTemplate, below any application code, so a rule that
 * only a service enforces cannot pass as enforced here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeAndScmConstraintTest extends PostgresTestBase {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------- attachments

    @Test
    void publicKnowledgeCannotBeAttachedToARequirement() {
        Fixture fixture = new Fixture();
        long publicDoc = fixture.publicDocument();

        // The document's own scope is NULL, so the three-column key finds no
        // matching (project, id, requirement) triple.
        assertThat(sqlStateOf(() -> fixture.attach(fixture.requirement, publicDoc)))
                .isEqualTo(FOREIGN_KEY_VIOLATION);
    }

    @Test
    void anAttachmentIsPinnedToExactlyTheDocumentsOwnRequirement() {
        Fixture fixture = new Fixture();
        long otherRequirement = fixture.requirement();
        long scopedDoc = fixture.attachmentDocument(fixture.requirement);

        assertThat(sqlStateOf(() -> fixture.attach(otherRequirement, scopedDoc)))
                .isEqualTo(FOREIGN_KEY_VIOLATION);

        fixture.attach(fixture.requirement, scopedDoc);
        assertThat(sqlStateOf(() -> fixture.attach(fixture.requirement, scopedDoc)))
                .isEqualTo(UNIQUE_VIOLATION);
    }

    /**
     * The counter-proof for D015.2. On a copy of the table whose requirement_id is
     * nullable, MATCH SIMPLE skips the whole three-column check and a document id
     * that exists nowhere is accepted. This is why the real column is NOT NULL,
     * and it fails loudly if anyone ever relaxes it.
     */
    @Test
    void aNullableRequirementIdWouldMakeTheOwnershipCheckEvaporate() {
        Fixture fixture = new Fixture();
        String table = "attachment_probe_" + SEQUENCE.incrementAndGet();
        jdbc.execute("create table " + table + " ("
                + "project_id bigint not null, requirement_id bigint, document_id bigint not null, "
                + "constraint fk_" + table + " foreign key (project_id, document_id, requirement_id) "
                + "references knowledge_document (project_id, id, source_requirement_id))");
        try {
            long nonexistentDocument = 999_999_999L;
            jdbc.update("insert into " + table + " (project_id, requirement_id, document_id) "
                    + "values (?, null, ?)", fixture.project, nonexistentDocument);

            assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class))
                    .as("a NULL in the referencing key disables the entire composite check")
                    .isEqualTo(1);
        } finally {
            jdbc.execute("drop table " + table);
        }
    }

    @Test
    void aDocumentsScopeMustAgreeWithItsType() {
        Fixture fixture = new Fixture();

        assertThat(sqlStateOf(() -> fixture.document("REQUIREMENT_ATTACHMENT", null)))
                .isEqualTo(CHECK_VIOLATION);
        assertThat(sqlStateOf(() -> fixture.document("PROJECT_KNOWLEDGE", fixture.requirement)))
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    void aFailedDocumentMustCarryItsReason() {
        Fixture fixture = new Fixture();

        assertThat(sqlStateOf(() -> jdbc.update(
                "update knowledge_document set status = 'FAILED' where id = ?", fixture.publicDocument())))
                .isEqualTo(CHECK_VIOLATION);
    }

    // ------------------------------------------------------------------ chunks

    @Test
    void aChunksDeclaredDimensionMustMatchItsVector() {
        Fixture fixture = new Fixture();
        long document = fixture.publicDocument();

        assertThat(sqlStateOf(() -> fixture.chunk(document, 1, "[1,2,3]", 1024)))
                .isEqualTo(CHECK_VIOLATION);

        fixture.chunk(document, 1, "[1,2,3]", 3);
        assertThat(sqlStateOf(() -> fixture.chunk(document, 1, "[4,5,6]", 3)))
                .isEqualTo(UNIQUE_VIOLATION);
    }

    /**
     * Records the measured behaviour that makes application-side validation the
     * only real defence (D015.3): the column takes any dimension, and one
     * mismatched row breaks every similarity query in that project.
     */
    @Test
    void aDimensionlessColumnAcceptsAnythingUntilAQueryNeedsThem() {
        Fixture fixture = new Fixture();
        long document = fixture.publicDocument();
        fixture.chunk(document, 1, "[1,2,3,4]", 4);
        fixture.chunk(document, 2, "[1,2,3]", 3);

        assertThat(jdbc.queryForObject("select count(*) from knowledge_chunk where document_id = ?",
                Integer.class, document)).isEqualTo(2);

        assertThat(sqlStateOf(() -> jdbc.queryForList(
                "select id from knowledge_chunk where project_id = ? "
                        + "order by embedding <=> '[1,2,3,4]'::vector limit 5", fixture.project)))
                .as("mixed dimensions poison the whole project's retrieval")
                .isEqualTo("22000");
    }

    // -------------------------------------------------------------------- scm

    @Test
    void repositoryIdentityIsUniqueAcrossEveryProject() {
        Fixture first = new Fixture();
        Fixture second = new Fixture();
        String shared = "octo/shared-" + SEQUENCE.incrementAndGet();
        first.repository("GITHUB", "github.com", shared);

        assertThat(sqlStateOf(() -> second.repository("GITHUB", "github.com", shared)))
                .as("one webhook delivery must never have two targets")
                .isEqualTo(UNIQUE_VIOLATION);

        // A different repository in the same instance is fine.
        second.repository();
    }

    @Test
    void aProjectHasAtMostOneActiveRepository() {
        Fixture fixture = new Fixture();
        fixture.repository();

        assertThat(sqlStateOf(fixture::repository)).isEqualTo(UNIQUE_VIOLATION);
    }

    @Test
    void removingAMemberClearsOnlyThePullRequestAuthorColumn() {
        Fixture fixture = new Fixture();
        long repository = fixture.repository();
        long author = fixture.member();
        long pullRequest = fixture.pullRequest(repository, 1, author);

        jdbc.update("delete from project_member_role where project_id = ? and user_id = ?",
                fixture.project, author);
        jdbc.update("delete from project_member where project_id = ? and user_id = ?",
                fixture.project, author);

        assertThat(jdbc.queryForMap("select project_id, author_user_id, author_external_user_id "
                + "from pull_request where id = ?", pullRequest))
                .containsEntry("author_user_id", null)
                .containsEntry("project_id", fixture.project)
                // The immutable snapshot survives: who opened it is a fact.
                .containsEntry("author_external_user_id", "gh-" + author);
    }

    @Test
    void anAssociationEventNeedsAnActorThatMatchesItsType() {
        Fixture fixture = new Fixture();
        long pullRequest = fixture.pullRequest(
                fixture.repository(), 1, fixture.member());

        assertThat(sqlStateOf(() -> fixture.event(pullRequest, "USER", null, null, fixture.requirement)))
                .isEqualTo(CHECK_VIOLATION);
        assertThat(sqlStateOf(() -> fixture.event(pullRequest, "SYSTEM", fixture.owner, null, fixture.requirement)))
                .isEqualTo(CHECK_VIOLATION);

        fixture.event(pullRequest, "SYSTEM", null, null, fixture.requirement);
        fixture.event(pullRequest, "USER", fixture.owner, fixture.requirement, null);
    }

    @Test
    void anAssociationEventMustActuallyRecordAChange() {
        Fixture fixture = new Fixture();
        long pullRequest = fixture.pullRequest(
                fixture.repository(), 1, fixture.member());

        assertThat(sqlStateOf(() -> fixture.event(pullRequest, "SYSTEM", null, null, null)))
                .isEqualTo(CHECK_VIOLATION);
        assertThat(sqlStateOf(() -> fixture.event(pullRequest, "SYSTEM", null,
                fixture.requirement, fixture.requirement)))
                .isEqualTo(CHECK_VIOLATION);
    }

    /**
     * D015.1, now closed. Batch 2 created {@code review_id} without a foreign key
     * because {@code review} did not exist, and asserted the column held only
     * NULLs so that adding the key later could not collide with history. Batch 3
     * created the table and added the key, so this test is now the other half of
     * that promise: the key is present, and it points where D015.1 said it would.
     */
    @Test
    void aiCallLogsReviewForeignKeyLandedInBatchThree() {
        // Asserted against the column the key has to include rather than against a
        // constraint name, so renaming the constraint cannot make this pass or fail
        // for the wrong reason.
        assertThat(jdbc.queryForObject(
                "select count(*) from pg_constraint c "
                        + "join pg_attribute a on a.attrelid = c.conrelid and a.attnum = any (c.conkey) "
                        + "where c.conrelid = 'ai_call_log'::regclass and c.contype = 'f' "
                        + "and a.attname = 'review_id'", Integer.class))
                .as("review_id must now be covered by a foreign key")
                .isOne();

        // And it must point at review, not at whatever happened to be handy.
        assertThat(jdbc.queryForObject(
                "select confrelid::regclass::text from pg_constraint c "
                        + "join pg_attribute a on a.attrelid = c.conrelid and a.attnum = any (c.conkey) "
                        + "where c.conrelid = 'ai_call_log'::regclass and c.contype = 'f' "
                        + "and a.attname = 'review_id'", String.class))
                .isEqualTo("review");

        // The composite keys batch 2 already had must still be there: a count that
        // only went up proves nothing if something else quietly went away.
        assertThat(jdbc.queryForList(
                "select conname from pg_constraint where conrelid = 'ai_call_log'::regclass "
                        + "and contype = 'f'", String.class))
                .hasSizeGreaterThan(1);
    }

    // ------------------------------------------------- cross-project, by the database

    /**
     * AC1's point is that isolation is enforced by the schema and not by a service
     * check somebody could route around, so every one of these writes goes straight
     * through JdbcTemplate.
     */
    @Test
    void noProjectScopedRowMayReachAcrossProjects() {
        Fixture mine = new Fixture();
        Fixture theirs = new Fixture();
        long myRepository = mine.repository();
        long myPullRequest = mine.pullRequest(myRepository, 1, mine.member());
        long theirRepository = theirs.repository();
        long theirPullRequest = theirs.pullRequest(theirRepository, 1, theirs.member());

        // A pull request cannot borrow another project's repository...
        assertThat(sqlStateOf(() -> mine.pullRequest(theirRepository, 2, mine.member())))
                .isEqualTo(FOREIGN_KEY_VIOLATION);

        // ...nor link to another project's requirement.
        assertThat(sqlStateOf(() -> jdbc.update(
                "update pull_request set requirement_id = ? where id = ?",
                theirs.requirement, myPullRequest)))
                .isEqualTo(FOREIGN_KEY_VIOLATION);
        jdbc.update("update pull_request set requirement_id = ? where id = ?",
                mine.requirement, myPullRequest);

        // An audit row cannot point at another project's pull request...
        assertThat(sqlStateOf(() -> mine.event(theirPullRequest, "SYSTEM", null, null, mine.requirement)))
                .isEqualTo(FOREIGN_KEY_VIOLATION);

        // ...nor at another project's requirement.
        assertThat(sqlStateOf(() -> mine.event(myPullRequest, "SYSTEM", null, null, theirs.requirement)))
                .isEqualTo(FOREIGN_KEY_VIOLATION);

        // And a call log cannot name another project's requirement.
        assertThat(sqlStateOf(() -> mine.callLog(theirs.requirement)))
                .isEqualTo(FOREIGN_KEY_VIOLATION);
        mine.callLog(mine.requirement);
    }

    // ------------------------------------------------------------------ helpers

    /** One project with an owner, a member and a requirement to hang things off. */
    private final class Fixture {

        private final long owner;
        private final long project;
        private final long requirement;

        private Fixture() {
            this.owner = account();
            this.project = requireId(jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "p-" + SEQUENCE.incrementAndGet(), owner));
            jdbc.update("with member as (insert into project_member (project_id, user_id) values (?, ?) returning project_id, user_id) insert into project_member_role (project_id, user_id, role) select project_id, user_id, 'LEADER' from member",
                    project, owner);
            this.requirement = requirement();
        }

        private long account() {
            return requireId(jdbc.queryForObject(
                    "insert into user_account (username, display_name, password_hash) values (?, 'Test User', 'x') returning id",
                    Long.class, "u-" + SEQUENCE.incrementAndGet()));
        }

        private long member() {
            long user = account();
            jdbc.update("with member as (insert into project_member (project_id, user_id) "
                            + "values (?, ?) returning project_id, user_id) "
                            + "insert into project_member_role (project_id, user_id, role) "
                            + "select project_id, user_id, 'DEVELOPER' from member",
                    project, user);
            return user;
        }

        private long requirement() {
            return requireId(jdbc.queryForObject(
                    "insert into requirement (project_id, status) values (?, 'DRAFT') returning id",
                    Long.class, project));
        }

        private long document(String sourceType, Long scope) {
            return requireId(jdbc.queryForObject(
                    "insert into knowledge_document (project_id, source_type, source_requirement_id, "
                            + "title, text, status) values (?, ?, ?, 'doc.md', 'body', 'READY') returning id",
                    Long.class, project, sourceType, scope));
        }

        private long publicDocument() {
            return document("PROJECT_KNOWLEDGE", null);
        }

        private long attachmentDocument(long scope) {
            return document("REQUIREMENT_ATTACHMENT", scope);
        }

        private void attach(long requirementId, long documentId) {
            jdbc.update("insert into requirement_attachment (project_id, requirement_id, document_id) "
                    + "values (?, ?, ?)", project, requirementId, documentId);
        }

        private void chunk(long documentId, int seq, String embedding, int dimension) {
            jdbc.update("insert into knowledge_chunk (project_id, document_id, seq, content, embedding, "
                    + "dimension) values (?, ?, ?, 'chunk', ?::vector, ?)",
                    project, documentId, seq, embedding, dimension);
        }

        /** A repository nobody else in this run will collide with. */
        private long repository() {
            return repository("GITHUB", "github.com", "octo/repo-" + SEQUENCE.incrementAndGet());
        }

        private long repository(String provider, String instance, String externalId) {
            return requireId(jdbc.queryForObject(
                    "insert into scm_repository (project_id, provider, instance_identity, external_id, "
                            + "api_base, encrypted_token, encrypted_secret) "
                            + "values (?, ?, ?, ?, 'https://api.github.com', 'tok', 'sec') returning id",
                    Long.class, project, provider, instance, externalId));
        }

        private long pullRequest(long repositoryId, int number, long authorUserId) {
            return requireId(jdbc.queryForObject(
                    "insert into pull_request (project_id, repository_id, external_number, base_sha, "
                            + "head_sha, review_input_fingerprint, changed_files, "
                            + "author_external_user_id, author_username, author_user_id) "
                            + "values (?, ?, ?, 'base', 'head', 'fp', '[]'::jsonb, ?, ?, ?) returning id",
                    Long.class, project, repositoryId, number,
                    "gh-" + authorUserId, "dev-" + authorUserId, authorUserId));
        }

        private void callLog(Long requirementId) {
            jdbc.update("insert into ai_call_log (project_id, requirement_id, use_case, model, "
                    + "latency_ms, status) values (?, ?, 'EMBEDDING', 'stub-model', 1, 'SUCCESS')",
                    project, requirementId);
        }

        private void event(long pullRequestId, String actorType, Long actorUserId, Long from, Long to) {
            jdbc.update("insert into pull_request_requirement_event (project_id, pull_request_id, "
                    + "from_requirement_id, to_requirement_id, actor_type, actor_user_id) "
                    + "values (?, ?, ?, ?, ?, ?)",
                    project, pullRequestId, from, to, actorType, actorUserId);
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
