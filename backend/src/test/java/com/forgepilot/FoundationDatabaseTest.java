package com.forgepilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FoundationDatabaseTest extends PostgresTestBase {

    /** Thirteen of the sixteen tables exist; review, finding and finding_event arrive with batch 3. */
    // Sixteen, which is the cap. ARCHITECTURE.md 2.1 fixes the model at exactly
    // this set, so a seventeenth table is a design change and not a migration.
    // Compared by name rather than by count on purpose: a count still passes when
    // one planned table is missing and one unplanned table took its place.
    private static final List<String> EXPECTED_TABLES = List.of(
            "acceptance_criterion", "ai_call_log", "finding", "finding_event",
            "knowledge_chunk", "knowledge_document", "project", "project_member",
            "pull_request", "pull_request_requirement_event", "requirement",
            "requirement_attachment", "requirement_revision", "review", "scm_repository",
            "user_account");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Test
    void postgresHasVectorAndOnlyTheMigratedTables() {
        Integer version = jdbc.queryForObject("select current_setting('server_version_num')::integer", Integer.class);
        assertThat(version).isGreaterThanOrEqualTo(150000);

        String extension = jdbc.queryForObject(
                "select extversion from pg_extension where extname = 'vector'", String.class);
        assertThat(extension).isNotBlank();

        Double distance = jdbc.queryForObject("select '[1,2,3]'::vector <-> '[1,2,4]'::vector", Double.class);
        assertThat(distance).isEqualTo(1.0d);

        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = 'public' and table_type = 'BASE TABLE' "
                        + "and table_name <> 'flyway_schema_history'",
                String.class);
        // Order is the database collation's business, not this test's. The set is
        // what matters: an unplanned table has to fail here.
        assertThat(tables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    }

    @Test
    void everyMigrationApplied() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "select version, description, success from flyway_schema_history order by installed_rank");

        assertThat(history).extracting(row -> row.get("version") + ":" + row.get("description"))
                .containsExactly("1:foundation", "2:auth project", "3:requirement",
                        "4:knowledge ai", "5:scm", "6:review");
        assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
    }

    @Test
    void actuatorHealthReportsUp() {
        assertThat(healthEndpoint.health().getStatus().getCode()).isEqualTo("UP");
    }
}
