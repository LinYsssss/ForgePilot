package com.example.codereview.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

class DingTalkNotifierTest {

    private HttpServer server;
    private final List<String> receivedUris = new ArrayList<>();
    private final List<String> receivedBodies = new ArrayList<>();
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/robot/send", exchange -> {
            receivedUris.add(exchange.getRequestURI().toString());
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"errcode\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(HttpStatus.OK.value(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/robot/send?access_token=t";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private DingTalkNotifier notifier(String secret, String keyword, String minRisk) {
        return new DingTalkNotifier(RestClient.builder(), baseUrl, secret, keyword, minRisk, "https://reposage.example");
    }

    private ReviewNotification notification(String risk, int issues, int high) {
        return new ReviewNotification(2L, "mall-order", 42L,
                "7fd1a60b01f91b314f59955a4e4d4e80d8edf11d", risk, issues, high, "发现支付校验缺失");
    }

    @Test
    void sendsSignedMarkdownMessage() {
        notifier("my-secret", "", "LOW").reviewCompleted(notification("HIGH", 3, 1));

        assertThat(receivedUris).hasSize(1);
        assertThat(receivedUris.get(0)).contains("timestamp=").contains("sign=");
        String body = receivedBodies.get(0);
        assertThat(body).contains("\"msgtype\":\"markdown\"");
        assertThat(body).contains("mall-order").contains("HIGH").contains("高危 1");
        assertThat(body).contains("7fd1a60b");            // commit 截断为短 sha
        assertThat(body).contains("reposage.example");    // 报告链接
    }

    @Test
    void keywordIsPrependedAndNoSignatureWhenSecretAbsent() {
        notifier("", "RepoSage", "LOW").reviewCompleted(notification("MEDIUM", 1, 0));

        assertThat(receivedUris.get(0)).doesNotContain("sign=");
        assertThat(receivedBodies.get(0)).contains("RepoSage");
    }

    @Test
    void riskBelowThresholdIsNotSent() {
        notifier("s", "", "HIGH").reviewCompleted(notification("LOW", 1, 0));
        assertThat(receivedUris).isEmpty();

        notifier("s", "", "HIGH").reviewCompleted(notification("HIGH", 1, 1));
        assertThat(receivedUris).hasSize(1);
    }

    @Test
    void deliveryFailureIsSwallowed() {
        DingTalkNotifier broken = new DingTalkNotifier(RestClient.builder(),
                "http://127.0.0.1:1/robot/send", "s", "", "LOW", "");
        // 不抛异常即通过:通知失败不能影响审查主流程
        broken.reviewCompleted(notification("HIGH", 1, 1));
    }

    @Test
    void enablingWithoutWebhookUrlFailsFast() {
        assertThatThrownBy(() -> new DingTalkNotifier(RestClient.builder(), "", "s", "", "LOW", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DINGTALK_WEBHOOK_URL");
    }
}
