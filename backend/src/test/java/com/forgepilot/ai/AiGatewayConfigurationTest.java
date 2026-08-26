package com.forgepilot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 当没有任何人配置过 provider 时会发生什么。
 *
 * <p>代码里任何地方都没有默认 base URI、也没有默认密钥
 * （以及 {@code quality-guidelines.md} 关于兜底凭据的规定），
 * 因此未配置的部署必须**拒绝**，而不是去随手抓一个什么来用。
 * 这次拒绝发生在调用时而非启动时，因为不应该为了一个运维尚未启用的功能
 * 而阻止应用启动。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"forgepilot.ai.base-url=", "forgepilot.ai.chat-model=stub-chat-model"})
class AiGatewayConfigurationTest extends PostgresTestBase {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private AiGateway gateway;

    @Autowired
    private AiCallLogRepository callLogs;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void withoutABaseUriTheGatewayRefusesAndAttemptsNothing() {
        long project = project();

        assertThatThrownBy(() -> gateway.chat("prompt", null, AiUseCase.REQUIREMENT_QUALITY,
                AiCallContext.ofProject(project)))
                .isInstanceOf(ApiException.class)
                .hasMessage("The AI provider is not configured.");
        assertThatThrownBy(() -> gateway.embed(List.of("text"), "stub-embedding-model",
                AiCallContext.ofProject(project)))
                .isInstanceOf(ApiException.class)
                .hasMessage("The AI provider is not configured.");

        // 什么都没尝试，因此什么都不记录：ai_call_log 存的是对 provider 的**尝试**，
        // 而不是配置错误。
        assertThat(callLogs.findByProjectIdOrderByIdAsc(project)).isEmpty();
    }

    private long project() {
        Long owner = jdbc.queryForObject(
                "insert into user_account (username, display_name, password_hash) values (?, 'Test User', 'x') returning id",
                Long.class, "ai-cfg-" + COUNTER.incrementAndGet());
        return jdbc.queryForObject(
                "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                Long.class, "ai-cfg-project-" + COUNTER.incrementAndGet(), owner);
    }
}
