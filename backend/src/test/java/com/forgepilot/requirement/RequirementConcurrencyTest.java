package com.forgepilot.requirement;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real row contention: the winning transaction commits after the request has started. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RequirementConcurrencyTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private RequirementService requirements;
    @Autowired private ProjectService projects;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @ParameterizedTest
    @ValueSource(strings = {"assignee", "reviewer", "publication", "status"})
    void aQueuedWriteCannotReviveACanceledRequirement(String operation) throws Exception {
        Fixture fixture = new Fixture(operation.equals("status")
                ? RequirementStatus.DRAFT : RequirementStatus.READY);

        HttpStatus result = whileLocked(fixture, () -> {
            switch (operation) {
                case "assignee" -> requirements.assign(fixture.project, fixture.owner, fixture.id, fixture.owner);
                case "reviewer" -> requirements.assignReviewer(fixture.project, fixture.owner, fixture.id, fixture.owner);
                case "publication" -> requirements.publishRevision(fixture.project, fixture.owner, fixture.id,
                        content("Late revision", "Late criterion"), "Concurrent change");
                case "status" -> requirements.changeStatus(fixture.project, fixture.owner, fixture.id, RequirementStatus.READY);
                default -> throw new AssertionError(operation);
            }
        }, connection -> update(connection, "update requirement set status = 'CANCELED' where id = ?", fixture.id));

        assertThat(result).isEqualTo(operation.equals("status")
                ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.CONFLICT);
        assertThat(jdbc.queryForMap("select status, current_revision_id, assignee_id, reviewer_id "
                + "from requirement where id = ?", fixture.id))
                .containsEntry("status", "CANCELED")
                .containsEntry("current_revision_id", fixture.revision)
                .containsEntry("assignee_id", null).containsEntry("reviewer_id", null);
        assertThat(requirements.listRevisions(fixture.project, fixture.owner, fixture.id)).hasSize(1);
    }

    @Test
    void aQueuedDraftEditCannotRewriteTheRevisionThatWasJustFrozen() throws Exception {
        Fixture fixture = new Fixture(RequirementStatus.DRAFT);

        HttpStatus result = whileLocked(fixture,
                () -> requirements.editDraft(fixture.project, fixture.owner, fixture.id,
                        content("Late title", "Late criterion")),
                connection -> update(connection, "update requirement set status = 'READY' where id = ?", fixture.id));

        assertThat(result).isEqualTo(HttpStatus.CONFLICT);
        RequirementDetail current = requirements.get(fixture.project, fixture.owner, fixture.id);
        assertThat(current.status()).isEqualTo(RequirementStatus.READY);
        assertThat(current.currentRevision().id()).isEqualTo(fixture.revision);
        assertThat(current.currentRevision().title()).isEqualTo("Original title");
        assertThat(current.currentRevision().acceptanceCriteria())
                .extracting(AcceptanceCriterionView::text).containsExactly("Original criterion");
    }

    @Test
    void aQueuedAssignmentPreservesAReviewerAssignedWhileItWaited() throws Exception {
        Fixture fixture = new Fixture(RequirementStatus.READY);

        HttpStatus result = whileLocked(fixture,
                () -> requirements.assign(fixture.project, fixture.owner, fixture.id, fixture.owner),
                connection -> update(connection, "update requirement set reviewer_id = ? where id = ?",
                        fixture.owner, fixture.id));

        assertThat(result).isNull();
        RequirementDetail current = requirements.get(fixture.project, fixture.owner, fixture.id);
        assertThat(current.status()).isEqualTo(RequirementStatus.IN_DEVELOPMENT);
        assertThat(current.assigneeId()).isEqualTo(fixture.owner);
        assertThat(current.reviewerId()).isEqualTo(fixture.owner);
    }

    @Test
    void aQueuedDeletionCannotDeleteTheSameRequirementTwice() throws Exception {
        Fixture fixture = new Fixture(RequirementStatus.CANCELED);

        HttpStatus result = whileLocked(fixture,
                () -> requirements.delete(fixture.project, fixture.owner, fixture.id),
                connection -> update(connection, "update requirement set deleted_at = now(), deleted_by = ? where id = ?",
                        fixture.owner, fixture.id));

        assertThat(result).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("select count(*) from project_deletion_record "
                + "where project_id = ? and resource_type = 'REQUIREMENT' and resource_id = ?",
                Integer.class, fixture.project, fixture.id)).isZero();
    }

    /** Lock both rows so the old draft writer also pauses, but only after its stale status check. */
    private HttpStatus whileLocked(Fixture fixture, Runnable request, SqlChange winner) throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lock(holder, "requirement", fixture.id);
            lock(holder, "requirement_revision", fixture.revision);
            int holderPid;
            try (var statement = holder.createStatement(); var row = statement.executeQuery("select pg_backend_pid()")) {
                assertThat(row.next()).isTrue();
                holderPid = row.getInt(1);
            }
            Future<HttpStatus> pending = pool.submit(() -> {
                try {
                    request.run();
                    return null;
                } catch (ApiException failure) {
                    return failure.getStatus();
                }
            });
            awaitBlocked(holderPid, pending);
            winner.apply(holder);
            holder.commit();
            return pending.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private void awaitBlocked(int holderPid, Future<?> pending) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity "
                    + "where datname = current_database() and ? = any(pg_blocking_pids(pid))",
                    Integer.class, holderPid);
            if (waiting != null && waiting > 0) return;
            assertThat(pending.isDone()).as("the writer must reach the held row before deciding").isFalse();
            Thread.sleep(20);
        }
        throw new AssertionError("The writer never reached the row lock");
    }

    private static void lock(Connection connection, String table, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select id from " + table + " where id = ? for update")) {
            statement.setLong(1, id);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); }
        }
    }

    private static void update(Connection connection, String sql, long... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setLong(i + 1, values[i]);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static RequirementContent content(String title, String text) {
        return new RequirementContent(title, null, null, List.of(new CriterionInput(null, text)));
    }

    @FunctionalInterface
    private interface SqlChange { void apply(Connection connection) throws SQLException; }

    private final class Fixture {
        private final long owner;
        private final long project;
        private final long id;
        private final long revision;

        private Fixture(RequirementStatus status) {
            int sequence = SEQUENCE.incrementAndGet();
            owner = jdbc.queryForObject("insert into user_account (username, display_name, password_hash) "
                    + "values (?, 'Test User', 'x') returning id", Long.class, "requirement-race-" + sequence);
            project = projects.create("Requirement race " + sequence, owner).id();
            RequirementDetail created = requirements.create(project, owner, content("Original title", "Original criterion"));
            id = created.id();
            revision = created.currentRevision().id();
            if (status != RequirementStatus.DRAFT) requirements.changeStatus(project, owner, id, status);
        }
    }
}
