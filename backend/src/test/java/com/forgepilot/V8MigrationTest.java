package com.forgepilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class V8MigrationTest extends PostgresTestBase {

    private static final String SCHEMA = "v8_upgrade_test";

    @Test
    void upgradesLegacyMemberRolesAndScmIdentityWithoutLosingTheirMeaning() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema(SCHEMA)
                .schemas(SCHEMA)
                .target(MigrationVersion.fromVersion("7"))
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement sql = connection.createStatement()) {
            connection.setSchema(SCHEMA);
            sql.executeUpdate("""
                    insert into user_account (id, username, password_hash)
                    values (101, 'legacy-user', 'hash');
                    insert into project (id, name, created_by, status)
                    values (201, 'Legacy project', 101, 'ACTIVE');
                    insert into project_member (
                        project_id, user_id, role, scm_external_user_id,
                        scm_username, scm_identity_verified_at)
                    values (201, 101, 'DEVELOPER', 'github-101', 'legacy-login', now());
                    insert into scm_repository (
                        id, project_id, provider, instance_identity, external_id,
                        api_base, encrypted_token, encrypted_secret)
                    values (301, 201, 'GITHUB', 'github.com', 'repo-301',
                        'https://api.github.com', 'token', 'secret');
                    """);

            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .defaultSchema(SCHEMA)
                    .schemas(SCHEMA)
                    .load()
                    .migrate();

            assertThat(value(sql, "select display_name from user_account where id = 101"))
                    .isEqualTo("legacy-user");
            assertThat(value(sql, """
                    select role from project_member_role
                    where project_id = 201 and user_id = 101
                    """)).isEqualTo("DEVELOPER");
            assertThat(value(sql, """
                    select verification_status || ':' || provider || ':' || instance_identity
                    from scm_identity where user_id = 101
                    """)).isEqualTo("LEGACY_UNCONFIRMED:GITHUB:github.com");
            assertThat(value(sql, """
                    select b.status || ':' || b.repository_id
                    from project_member_scm_binding b where b.user_id = 101
                    """)).isEqualTo("LEGACY_UNCONFIRMED:301");
            assertThat(value(sql, """
                    select count(*)::text from information_schema.columns
                    where table_schema = 'v8_upgrade_test' and table_name = 'project_member'
                      and column_name in ('role', 'scm_external_user_id', 'scm_username',
                                          'scm_identity_verified_at')
                    """)).isEqualTo("0");
        } finally {
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                    Statement sql = connection.createStatement()) {
                sql.execute("drop schema if exists " + SCHEMA + " cascade");
            }
        }
    }

    private static String value(Statement sql, String query) throws Exception {
        try (ResultSet result = sql.executeQuery(query)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
