package com.forgepilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * V10 adds two nullable columns to {@code requirement} plus one new table, so the
 * risk it carries is the same one V9 carried: {@code ALTER TABLE ... ADD
 * CONSTRAINT ... CHECK} validates the rows that are already in the table.
 * {@code ck_requirement_deleted_shape} is satisfied by a pre-existing row only
 * because both columns arrive NULL together — a shape check written the other way
 * round would pass every test against a freshly migrated database and then fail
 * the migration outright on any deployment that has ever recorded a requirement.
 *
 * <p>{@code FoundationDatabaseTest} migrates an empty schema and therefore cannot
 * see that failure at all. This test writes a pre-V10 requirement first, which is
 * the only shape in which the constraint has anything to validate.
 */
class V10MigrationTest extends PostgresTestBase {

    private static final String SCHEMA = "v10_upgrade_test";

    @Test
    void aRequirementWrittenBeforeTheUpgradeStaysLegalAndUndeleted() throws Exception {
        migrate("9");

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement sql = connection.createStatement()) {
            connection.setSchema(SCHEMA);
            sql.executeUpdate("""
                    insert into user_account (id, username, password_hash, display_name)
                    values (111, 'legacy-lead', 'hash', 'Legacy Lead');
                    insert into project (id, name, created_by, status)
                    values (211, 'Legacy project', 111, 'ACTIVE');
                    insert into requirement (id, project_id, status)
                    values (311, 211, 'CANCELED');
                    insert into requirement_revision (
                        id, project_id, requirement_id, seq, title, created_by)
                    values (411, 211, 311, 1, 'Legacy requirement', 111);
                    update requirement set current_revision_id = 411 where id = 311;
                    """);

            migrate(null);

            assertThat(value(sql, """
                    select coalesce(deleted_at::text, '-') || ':' || coalesce(deleted_by::text, '-')
                    from requirement where id = 311
                    """))
                    .as("a requirement that predates the columns is not retroactively deleted")
                    .isEqualTo("-:-");
            assertThat(value(sql, "select count(*)::text from project_deletion_record"))
                    .as("and the ledger starts empty rather than being backfilled with guesses")
                    .isEqualTo("0");
        } finally {
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                    Statement sql = connection.createStatement()) {
                sql.execute("drop schema if exists " + SCHEMA + " cascade");
            }
        }
    }

    /** Migrates the throwaway schema up to {@code target}, or all the way when null. */
    private static void migrate(String target) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema(SCHEMA)
                .schemas(SCHEMA)
                .target(target == null ? MigrationVersion.LATEST : MigrationVersion.fromVersion(target))
                .load()
                .migrate();
    }

    private static String value(Statement sql, String query) throws Exception {
        try (ResultSet result = sql.executeQuery(query)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
