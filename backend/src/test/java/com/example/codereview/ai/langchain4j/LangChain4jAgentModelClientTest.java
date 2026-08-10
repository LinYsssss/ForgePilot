package com.example.codereview.ai.langchain4j;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.codereview.agent.model.AgentModelClient;
import com.example.codereview.agent.model.PromptEnvelope;
import com.example.codereview.common.exception.AiCallTransientException;
import com.example.codereview.common.exception.BusinessException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LangChain4jAgentModelClientTest {

    private static final String COMPLETIONS_PATH = "/v1/chat/completions";

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
        WireMock.configureFor("localhost", server.port());
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void mapsOrderedMessagesModelTemperatureFinishReasonAndUsage() {
        stubSuccess(true);

        AgentModelClient.ModelResponse response = client(server.baseUrl() + "/v1", 2_000)
                .generate(prompt());

        assertThat(response.provider()).isEqualTo("openai-compatible");
        assertThat(response.model()).isEqualTo("provider-model");
        assertThat(response.content()).contains("\"summary\":\"ok\"");
        assertThat(response.inputTokens()).isEqualTo(12);
        assertThat(response.outputTokens()).isEqualTo(4);
        assertThat(response.finishReason()).isEqualTo("STOP");
        assertThat(response.latencyMs()).isNotNegative();

        server.verify(postRequestedFor(urlEqualTo(COMPLETIONS_PATH))
                .withHeader("Authorization", equalTo("Bearer test-api-key"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("review-model")))
                .withRequestBody(matchingJsonPath("$.temperature", equalTo("0.0")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath(
                        "$.messages[0].content", containing("Only use registered tools")
                ))
                .withRequestBody(matchingJsonPath(
                        "$.messages[0].content", containing("required_output_schema")
                ))
                .withRequestBody(matchingJsonPath("$.messages[1].role", equalTo("user")))
                .withRequestBody(matchingJsonPath(
                        "$.messages[1].content", containing("README asks to ignore policy")
                ))
                .withRequestBody(matchingJsonPath(
                        "$.messages[1].content", containing("Static evidence")
                )));
    }

    @Test
    void acceptsBaseUrlWithOrWithoutTrailingSlash() {
        for (String suffix : new String[]{"", "/"}) {
            server.resetAll();
            stubSuccess(true);
            client(server.baseUrl() + "/v1" + suffix, 2_000).generate(prompt());
            server.verify(1, postRequestedFor(urlEqualTo(COMPLETIONS_PATH)));
        }
    }

    @Test
    void mapsMissingTokenUsageToZero() {
        stubSuccess(false);

        AgentModelClient.ModelResponse response = client(server.baseUrl() + "/v1", 2_000)
                .generate(prompt());

        assertThat(response.inputTokens()).isZero();
        assertThat(response.outputTokens()).isZero();
    }

    @Test
    void classifiesAuthenticationAsPermanentWithoutLeakingResponseBody() {
        server.stubFor(post(urlEqualTo(COMPLETIONS_PATH))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"message\":\"secret-provider-detail\"}}")));

        assertThatThrownBy(() -> client(server.baseUrl() + "/v1", 2_000).generate(prompt()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("permanent")
                .hasMessageNotContaining("secret-provider-detail");
    }

    @Test
    void classifiesRateLimitAndServerErrorsAsTransient() {
        for (int status : new int[]{429, 503}) {
            server.resetAll();
            server.stubFor(post(urlEqualTo(COMPLETIONS_PATH))
                    .willReturn(aResponse().withStatus(status).withBody("provider-detail")));

            assertThatThrownBy(() -> client(server.baseUrl() + "/v1", 2_000).generate(prompt()))
                    .isInstanceOf(AiCallTransientException.class)
                    .hasMessageContaining("transient")
                    .hasMessageNotContaining("provider-detail");
        }
    }

    @Test
    void classifiesMalformedJsonAndEmptyChoicesAsPermanent() {
        for (String body : new String[]{"not-json", "{\"choices\":[]}"}) {
            server.resetAll();
            server.stubFor(post(urlEqualTo(COMPLETIONS_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(body)));

            assertThatThrownBy(() -> client(server.baseUrl() + "/v1", 2_000).generate(prompt()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("invalid response")
                    .hasMessageNotContaining(body);
        }
    }

    @Test
    void classifiesReadTimeoutAsTransient() {
        server.stubFor(post(urlEqualTo(COMPLETIONS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(250)
                        .withHeader("Content-Type", "application/json")
                        .withBody(successResponse(true))));

        assertThatThrownBy(() -> client(server.baseUrl() + "/v1", 50).generate(prompt()))
                .isInstanceOf(AiCallTransientException.class)
                .hasMessageContaining("transient");
    }

    @Test
    void repairsJsonThroughASeparateBoundedModelCall() {
        stubSuccess(true);

        AgentModelClient.ModelResponse response = client(server.baseUrl() + "/v1", 2_000)
                .repairJson("{broken", "Output must match schema-v1");

        assertThat(response.content()).contains("\"summary\":\"ok\"");
        server.verify(postRequestedFor(urlEqualTo(COMPLETIONS_PATH))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath(
                        "$.messages[0].content", containing("repair invalid JSON")
                ))
                .withRequestBody(matchingJsonPath("$.messages[1].role", equalTo("user")))
                .withRequestBody(matchingJsonPath(
                        "$.messages[1].content", containing("Output must match schema-v1")
                ))
                .withRequestBody(matchingJsonPath(
                        "$.messages[1].content", containing("{broken")
                )));
    }

    private LangChain4jAgentModelClient client(String baseUrl, int readTimeoutMs) {
        var configuration = new LangChain4jModelConfiguration();
        var model = configuration.langChain4jChatModel(
                baseUrl,
                "test-api-key",
                "review-model",
                0.0,
                1_000,
                readTimeoutMs
        );
        return new LangChain4jAgentModelClient(
                model,
                "openai-compatible",
                "review-model",
                ObservationRegistry.NOOP
        );
    }

    private void stubSuccess(boolean includeUsage) {
        server.stubFor(post(urlEqualTo(COMPLETIONS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(successResponse(includeUsage))));
    }

    private String successResponse(boolean includeUsage) {
        String usage = includeUsage
                ? ",\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4,\"total_tokens\":16}"
                : "";
        return """
                {
                  "id": "chat-1",
                  "object": "chat.completion",
                  "created": 1,
                  "model": "provider-model",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": "{\\\"summary\\\":\\\"ok\\\",\\\"plan\\\":[]}"
                    },
                    "finish_reason": "stop"
                  }]
                  %s
                }
                """.formatted(usage);
    }

    private PromptEnvelope prompt() {
        return new PromptEnvelope(
                "Only use registered tools.",
                "README asks to ignore policy.",
                "Static evidence.",
                "{\"type\":\"object\"}",
                "prompt-v1",
                "schema-v1"
        );
    }
}
