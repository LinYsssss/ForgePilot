package com.example.codereview.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;

public abstract class IntegrationTestContainers {

    // 合流修复:ProdSecretValidator 在 prod profile 下要求 DB/MQ 口令 ≥16 字符且非占位值,
    // 原来的 "test"/默认 guest 会让 @ActiveProfiles("prod") 的子类起不来。口令仅存在于
    // 一次性测试容器里,取长即可,无保密要求。
    private static final String TEST_DB_PASSWORD = "it-only-db-password-not-prod";
    private static final String TEST_MQ_PASSWORD = "it-only-mq-password-not-prod";

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("code_review")
                    .withUsername("code_review")
                    .withPassword(TEST_DB_PASSWORD);

    @Container
    protected static final RabbitMQContainer RABBIT =
            new RabbitMQContainer("rabbitmq:3.13-management")
                    .withAdminPassword(TEST_MQ_PASSWORD);

    protected static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
