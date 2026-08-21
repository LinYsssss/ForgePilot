package com.forgepilot;

import java.time.Duration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One real PostgreSQL 15 + pgvector for the whole test run. The container is
 * started once from a static initializer and reaped by Ryuk at JVM exit, so
 * every database test shares it instead of paying a fresh startup per class.
 *
 * <p>There is deliberately no "skip when Docker is missing" branch: a database
 * test that cannot run must fail the build.
 */
public abstract class PostgresTestBase {

    private static final String IMAGE = "pgvector/pgvector:0.8.6-pg15-bookworm";

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("forgepilot")
            .withUsername("forgepilot")
            .withPassword("forgepilot")
            .withStartupTimeout(Duration.ofMinutes(3));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
