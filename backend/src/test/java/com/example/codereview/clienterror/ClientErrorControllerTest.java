package com.example.codereview.clienterror;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 错误上报端点的 HTTP 层行为:匿名可达(permitAll)、字段校验 400、专用限流预算 429。
 * 限流按来源 IP 计桶(匿名流量),各用例用不同的 X-Forwarded-For 隔离桶
 * (默认受信代理含 127.0.0.1,MockMvc 的 remoteAddr 恰是它,转发头会被采信)。
 */
@SpringBootTest(properties = {
        "app.security.token-secret=test-secret",
        "app.security.token-encrypt-key=test-encrypt-key",
        "app.git.allow-local-path=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false",
        // 收小专用预算,429 用例不必真打 10 发;同时把它与其它上下文隔离开
        "app.ratelimit.client-errors-limit=5"
})
@AutoConfigureMockMvc
class ClientErrorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("client.error");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private MockHttpServletRequestBuilder report(String sourceIp, Map<String, Object> payload) throws Exception {
        return post("/api/client-errors")
                .header("X-Forwarded-For", sourceIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload));
    }

    private Map<String, Object> validPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("message", "Uncaught TypeError: x is not a function");
        payload.put("stack", "TypeError: x is not a function\n    at submit (app.js:10:3)");
        payload.put("url", "https://reposage.local/agent");
        payload.put("ts", 1755150000000L);
        return payload;
    }

    /** 端点的存在意义:登录前的 JS 崩溃也要能报上来——不带任何凭据必须 200。 */
    @Test
    void anonymousReportIsAcceptedAndLogged() throws Exception {
        mockMvc.perform(report("198.51.100.10", validPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getMarkerList())
                .extracting(org.slf4j.Marker::getName).containsExactly("CLIENT_ERROR");
        assertThat(appender.list.get(0).getFormattedMessage()).contains("Uncaught TypeError");
    }

    @Test
    void missingRequiredFieldsAre400AndNothingIsLogged() throws Exception {
        Map<String, Object> noMessage = validPayload();
        noMessage.remove("message");
        Map<String, Object> noUrl = validPayload();
        noUrl.remove("url");
        Map<String, Object> noTs = validPayload();
        noTs.remove("ts");

        mockMvc.perform(report("198.51.100.11", noMessage)).andExpect(status().isBadRequest());
        mockMvc.perform(report("198.51.100.11", noUrl)).andExpect(status().isBadRequest());
        mockMvc.perform(report("198.51.100.11", noTs)).andExpect(status().isBadRequest());

        // 校验不过的载荷不许落日志:空 message 没有诊断价值,只会成为免费的日志写入口
        assertThat(appender.list).isEmpty();
    }

    /** 专用预算(此上下文收成 5/分/IP)独立于通用 120/分:打满后 429 且带 Retry-After。 */
    @Test
    void floodFromOneSourceIsThrottledWithRetryAfter() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(report("198.51.100.12", validPayload())).andExpect(status().isOk());
        }
        mockMvc.perform(report("198.51.100.12", validPayload()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // 只有放行的 5 条落了日志,被限的那条没有
        assertThat(appender.list).hasSize(5);
    }
}
