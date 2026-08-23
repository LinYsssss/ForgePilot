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

    /** 十六张表中已有十三张；review、finding 与 finding_event 随批次 3 到位。 */
    // 十六张，也就是上限。ARCHITECTURE.md 2.1 把数据模型固定为**恰好**这一组，
    // 因此第十七张表是一次设计变更，而不是一次迁移。
    // 刻意按**名字**而不是按数量比对：只按数量比，会在「少了一张计划内的表、
    // 又多出一张计划外的表顶替它」时照样通过。
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
        // 顺序是数据库排序规则的事，不是本测试的事。要紧的是这个**集合**：
        // 任何计划外的表都必须在这里失败。
        assertThat(tables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    }

    @Test
    void everyMigrationApplied() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "select version, description, success from flyway_schema_history order by installed_rank");

        assertThat(history).extracting(row -> row.get("version") + ":" + row.get("description"))
                .containsExactly("1:foundation", "2:auth project", "3:requirement",
                        "4:knowledge ai", "5:scm", "6:review", "7:pull request title");
        assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
    }

    @Test
    void actuatorHealthReportsUp() {
        assertThat(healthEndpoint.health().getStatus().getCode()).isEqualTo("UP");
    }
}
