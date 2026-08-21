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

    /** Batch 1 owns exactly these; the remaining ten tables arrive with their own phase. */
    private static final List<String> EXPECTED_TABLES = List.of(
            "acceptance_criterion", "project", "project_member",
            "requirement", "requirement_revision", "user_account");

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
                        + "and table_name <> 'flyway_schema_history' order by table_name",
                String.class);
        assertThat(tables).containsExactlyElementsOf(EXPECTED_TABLES);
    }

    @Test
    void everyMigrationApplied() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "select version, description, success from flyway_schema_history order by installed_rank");

        assertThat(history).extracting(row -> row.get("version") + ":" + row.get("description"))
                .containsExactly("1:foundation", "2:auth project", "3:requirement");
        assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
    }

    @Test
    void actuatorHealthReportsUp() {
        assertThat(healthEndpoint.health().getStatus().getCode()).isEqualTo("UP");
    }
}
