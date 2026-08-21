package com.forgepilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FoundationDatabaseTest {

    private static final String IMAGE = "pgvector/pgvector:0.8.6-pg15-bookworm";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("forgepilot")
            .withUsername("forgepilot")
            .withPassword("forgepilot")
            .withStartupTimeout(Duration.ofMinutes(3));

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private HealthEndpoint healthEndpoint;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void cleanPostgresHasVectorAndOnlyFlywayMetadata() {
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
        assertThat(tables).isEmpty();

        Map<String, Object> migration = jdbc.queryForMap(
                "select version, description, success from flyway_schema_history where version = '1'");
        assertThat(migration.get("description")).isEqualTo("foundation");
        assertThat(migration.get("success")).isEqualTo(true);
    }

    @Test
    void actuatorHealthReportsUp() {
        assertThat(healthEndpoint.health().getStatus().getCode()).isEqualTo("UP");
    }
}
