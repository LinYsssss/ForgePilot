package com.example.codereview.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class LegacySchemaMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("code_review")
                    .withUsername("code_review")
                    .withPassword("test")
                    .withInitScript("db/legacy-schema.sql");

    @Test
    void legacySchemaUpgradesToCurrentColumnsAndIndexes() {
        var flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();

        var jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ));
        assertThat(columnExists(jdbc, "review_task", "base_commit_id_normalized")).isTrue();
        assertThat(columnExists(jdbc, "review_task", "pull_request_id")).isTrue();
        assertThat(columnExists(jdbc, "user_account", "session_version")).isTrue();
        assertThat(columnExists(jdbc, "feedback", "updated_at")).isTrue();
        assertThat(columnExists(jdbc, "ai_call_log", "total_tokens")).isTrue();
        assertThat(indexExists(jdbc, "uq_review_task_idempotency")).isTrue();
    }

    private boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = ?
                  and column_name = ?
                """, Integer.class, table, column);
        return count != null && count > 0;
    }

    private boolean indexExists(JdbcTemplate jdbc, String index) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from pg_indexes
                where schemaname = 'public'
                  and indexname = ?
                """, Integer.class, index);
        return count != null && count > 0;
    }
}
