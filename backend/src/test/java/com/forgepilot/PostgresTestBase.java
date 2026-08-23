package com.forgepilot;

import java.time.Duration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 整轮测试共用**一个**真实的 PostgreSQL 15 + pgvector。容器由静态初始化块启动一次，
 * 并在 JVM 退出时由 Ryuk 回收，因此所有数据库测试共享它，
 * 而不是每个类都付一次冷启动的代价。
 *
 * <p>这里**刻意**没有「Docker 不存在就跳过」的分支：
 * 一个跑不起来的数据库测试必须让构建失败。
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
        // 它在生产环境中没有任何兜底声明，因此缺了它上下文就起不来。
        // 在这里注入，而不是在 application.yml 里给默认值：一个默认值就是一把
        // 到处都能悄无声息生效的弱密钥，而这恰恰是「失败即关闭」所要防止的。
        // Compose 与 CI 各自注入它们同样是假的值。
        registry.add("forgepilot.scm.secret-key", () -> "test-only-not-a-real-key");
    }
}
