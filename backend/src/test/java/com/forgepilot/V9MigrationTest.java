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
 * V9 only adds columns, so the risk it carries is not a botched transformation —
 * it is that {@code ALTER TABLE ... ADD CONSTRAINT ... CHECK} validates the rows
 * that are already in the table. A CHECK that did not tolerate NULL would pass
 * every test against a freshly migrated database and then fail the migration
 * outright on any deployment that has ever recorded a finding.
 *
 * <p>{@code FoundationDatabaseTest} migrates an empty schema and therefore cannot
 * see that failure at all. This test writes a pre-V9 finding first, which is the
 * only shape in which the constraint has anything to validate.
 */
class V9MigrationTest extends PostgresTestBase {

    private static final String SCHEMA = "v9_upgrade_test";

    @Test
    void aFindingWrittenBeforeTheUpgradeStaysLegalAndKeepsItsHashes() throws Exception {
        migrate("8");

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement sql = connection.createStatement()) {
            connection.setSchema(SCHEMA);
            sql.executeUpdate("""
                    insert into user_account (id, username, password_hash, display_name)
                    values (101, 'legacy-user', 'hash', 'Legacy User');
                    insert into project (id, name, created_by, status)
                    values (201, 'Legacy project', 101, 'ACTIVE');
                    insert into scm_repository (
                        id, project_id, provider, instance_identity, external_id,
                        api_base, encrypted_token, encrypted_secret)
                    values (301, 201, 'GITHUB', 'github.com', 'repo-301',
                        'https://api.github.com', 'token', 'secret');
                    insert into pull_request (
                        id, project_id, repository_id, external_number, base_sha, head_sha,
                        review_input_fingerprint, changed_files, author_external_user_id,
                        author_username)
                    values (401, 201, 301, 7, 'base-sha', 'head-sha', 'fingerprint',
                        '[]'::jsonb, 'gh-101', 'legacy-login');
                    insert into review (
                        id, project_id, pull_request_id, head_sha, review_input_fingerprint,
                        status, execution_attempt)
                    values (501, 201, 401, 'head-sha', 'fingerprint', 'COMPLETED', 0);
                    insert into finding (
                        id, project_id, review_id, review_attempt, finding_type, path, line,
                        evidence, finding_key, evidence_hash, basis_hash, continuity)
                    values (601, 201, 501, 0, 'CODE_QUALITY', 'src/A.java', 3,
                        'class A {}', 'key-601', 'evidence-hash', 'basis-hash', 'NEW');
                    """);

            migrate(null);

            assertThat(value(sql, """
                    select coalesce(category, '-') || ':' || coalesce(explanation, '-')
                        || ':' || coalesce(suggestion, '-') || ':' || coalesce(confidence, '-')
                    from finding where id = 601
                    """))
                    .as("the row predates all four columns, so they are genuinely absent rather "
                            + "than an empty string the model never wrote")
                    .isEqualTo("-:-:-:-");
            assertThat(value(sql, """
                    select finding_key || ':' || evidence_hash || ':' || basis_hash
                    from finding where id = 601
                    """))
                    .as("and V9 recomputes no hash, so every inherited suppression still matches")
                    .isEqualTo("key-601:evidence-hash:basis-hash");
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
