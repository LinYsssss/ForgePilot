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
 * What happens when nobody has configured a provider.
 *
 * <p>There is no default base URI and no default key anywhere in the code
 * (D015.8, and {@code quality-guidelines.md} on fallback credentials), so an
 * unconfigured deployment must refuse rather than reach for something. The
 * refusal happens at the call and not at startup, because batch 2 must not stop
 * an application from booting for a feature its operator has not enabled yet.
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

        // Nothing was attempted, so nothing is recorded: ai_call_log holds
        // attempts against a provider, not configuration mistakes.
        assertThat(callLogs.findByProjectIdOrderByIdAsc(project)).isEmpty();
    }

    private long project() {
        Long owner = jdbc.queryForObject(
                "insert into user_account (username, password_hash) values (?, 'x') returning id",
                Long.class, "ai-cfg-" + COUNTER.incrementAndGet());
        return jdbc.queryForObject(
                "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                Long.class, "ai-cfg-project-" + COUNTER.incrementAndGet(), owner);
    }
}
