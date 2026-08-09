package com.example.codereview.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.support.IntegrationTestContainers;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

@ActiveProfiles("prod")
@SpringBootTest(properties = {
        "app.review.inline=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "app.security.token-secret=test-secret-at-least-32-characters",
        "app.security.token-encrypt-key=test-encryption-key-at-least-32",
        // 合流修复:prod profile 下 ProdSecretValidator 要求签名密钥非空且 ≥16 字符。
        "app.sandbox.signing-secret=it-only-signing-secret-not-prod",
        // 同上:prod profile 要求工具镜像按完整 64 位小写 hex digest 固定(r2 新增快速失败)。
        "app.sandbox.tool-image=it-only/sandbox-tools@sha256:"
                + "0000000000000000000000000000000000000000000000000000000000000000"
})
@Testcontainers(disabledWithoutDocker = true)
class InfrastructureIntegrationTest extends IntegrationTestContainers {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        register(registry);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RabbitTemplate rabbit;

    @Test
    void migrationsAndRabbitConnectionAreAvailable() {
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history",
                Integer.class
        )).isPositive();
        Boolean channelOpen = rabbit.execute(channel -> channel.isOpen());
        assertThat(channelOpen).isTrue();
    }
}
