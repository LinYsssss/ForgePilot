package com.example.codereview.characterization;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.codereview.agent.prompt.AgentPromptAssembler;
import com.example.codereview.agent.prompt.PromptTemplateRegistry;
import com.example.codereview.ai.OpenAiCompatibleReviewClient;
import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.AiCallTransientException;
import com.example.codereview.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 特征测试(锁现状,design.md 特征测试策略):批C Stage 2 把
 * {@code OpenAiCompatibleReviewClient.review} 的失败分类 catch 阶梯收敛进共享分类器之前,
 * 该阶梯没有任何直接覆盖。本测试只锁当下可观察的行为,不判断其对错:
 *
 * <ol>
 *   <li>HTTP 429 → {@code AiCallTransientException},消息以「AI 调用被限流(429): 」开头(可重试);</li>
 *   <li>其余 4xx → {@code BusinessException}(AI_CALL_FAILED),消息以「AI 调用失败: 」开头(不重试);</li>
 *   <li>HTTP 5xx → {@code AiCallTransientException},消息以「AI 服务端错误(5xx): 」开头;</li>
 *   <li>连接被拒 → {@code AiCallTransientException},消息以「AI 调用网络异常: 」开头;</li>
 *   <li>响应体提取阶段的读超时 → 包装类型是 {@code RestClientException}(非 ResourceAccessException),
 *       落入兜底分支成为**永久** {@code BusinessException},仅消息里带「读超时」运维提示
 *       (describeFailure 的 Javadoc 即为此路径而写;首跑实测证实,见任务留档);</li>
 *   <li>响应体解析失败产生的 {@code BusinessException}(AI_RESPONSE_INVALID)原样穿出,不被兜底分支再包一层。</li>
 * </ol>
 *
 * <p>先对未改动代码跑绿,收敛(引入 AiTransientFailureClassifier)后测试原样不动即行为不变。
 * 将来可被针对该客户端的正经单测替换。
 *
 * <p>另:r7-D4 temperature 对齐后,本类顺带承接「请求体 temperature 来自 app.ai.temperature 配置」
 * 的回归断言(复用同一 WireMock 台架,见 {@link #configuredTemperatureZeroLandsInRequestBody()};
 * 该条不属于失败分类特征,是行为变更后的正向锁定)。
 */
class AiReviewFailureClassificationCharacterizationTest {

    private static final String COMPLETIONS_PATH = "/chat/completions";

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void rateLimit429SurfacesAsTransientThrottleMessage() {
        stubStatus(429);

        assertThatThrownBy(() -> client(server.baseUrl(), 2_000).review("diff", null))
                .isInstanceOf(AiCallTransientException.class)
                .hasMessageStartingWith("AI 调用被限流(429): ");
    }

    @Test
    void other4xxSurfacesAsPermanentAiCallFailed() {
        stubStatus(400);

        assertThatThrownBy(() -> client(server.baseUrl(), 2_000).review("diff", null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_CALL_FAILED);
                    assertThat(ex).hasMessageStartingWith("AI 调用失败: ");
                })
                .isNotInstanceOf(AiCallTransientException.class);
    }

    @Test
    void serverError5xxSurfacesAsTransientServerMessage() {
        stubStatus(503);

        assertThatThrownBy(() -> client(server.baseUrl(), 2_000).review("diff", null))
                .isInstanceOf(AiCallTransientException.class)
                .hasMessageStartingWith("AI 服务端错误(5xx): ");
    }

    @Test
    void connectionRefusedSurfacesAsTransientNetworkMessage() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> client("http://127.0.0.1:" + unusedPort, 2_000).review("diff", null))
                .isInstanceOf(AiCallTransientException.class)
                .hasMessageStartingWith("AI 调用网络异常: ");
    }

    @Test
    void readTimeoutDuringBodyExtractionSurfacesAsPermanentWithOpsHint() {
        // 写实:读超时发生在响应体提取阶段时,Spring 包装为 RestClientException(不是
        // ResourceAccessException),现状落入兜底 catch (Exception) → 永久失败不重试。
        // 收敛进共享分类器时必须原样保留这一分支走向,不得「顺手」改成瞬时。
        server.stubFor(post(urlEqualTo(COMPLETIONS_PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(400).withBody("{}")));

        assertThatThrownBy(() -> client(server.baseUrl(), 50).review("diff", null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_CALL_FAILED);
                    assertThat(ex).hasMessageStartingWith("AI 调用失败: ");
                    assertThat(ex).hasMessageContaining("读超时");
                })
                .isNotInstanceOf(AiCallTransientException.class);
    }

    @Test
    void parseFailureBusinessExceptionPassesThroughWithoutReclassification() {
        server.stubFor(post(urlEqualTo(COMPLETIONS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("not-json")));

        assertThatThrownBy(() -> client(server.baseUrl(), 2_000).review("diff", null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_RESPONSE_INVALID);
                    assertThat(ex).hasMessageContaining("AI 响应不是有效 JSON");
                });
    }

    @Test
    void configuredTemperatureZeroLandsInRequestBody() throws IOException {
        // r7-D4 temperature 对齐:temperature 不再硬编码 0.2,改由 app.ai.temperature 注入
        // (默认 0.0,与 LangChain4j 路径同键同默认)。本类直构不走 Spring,@Value 默认值不生效,
        // 故按默认值语义显式传 0.0(见 client(...)),再用 WireMock 捕获真实请求体,
        // 断言序列化出的 temperature 恰为 0.0 而非旧硬编码 0.2——评测 manifest 的
        // fixedRun.temperature=0 自此对 legacy chat 路径成立。
        server.stubFor(post(urlEqualTo(COMPLETIONS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}")));

        client(server.baseUrl(), 2_000).review("diff", null);

        List<LoggedRequest> requests = server.findAll(postRequestedFor(urlEqualTo(COMPLETIONS_PATH)));
        assertThat(requests).hasSize(1);
        JsonNode body = new ObjectMapper().readTree(requests.get(0).getBodyAsString());
        assertThat(body.path("temperature").isNumber())
                .as("请求体必须携带数值型 temperature 字段(缺字段时 path().asDouble() 会假绿,先锁类型)")
                .isTrue();
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.0);
    }

    private OpenAiCompatibleReviewClient client(String baseUrl, int readTimeoutMs) {
        return new OpenAiCompatibleReviewClient(
                RestClient.builder(),
                new ObjectMapper(),
                new AgentPromptAssembler(new PromptTemplateRegistry()),
                baseUrl,
                "test-api-key",
                "review-model",
                // 直构不经 Spring 环境,@Value 默认值不会生效;此处显式传 app.ai.temperature
                // 的默认值 0.0,保持与配置默认同义(r7-D4 对齐)
                0.0,
                1_000,
                readTimeoutMs
        );
    }

    private void stubStatus(int status) {
        server.stubFor(post(urlEqualTo(COMPLETIONS_PATH))
                .willReturn(aResponse().withStatus(status).withBody("provider-detail")));
    }
}
