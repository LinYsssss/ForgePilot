package com.example.codereview.ai.langchain4j;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.codereview.ai.AiCallTransientException;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.rag.EmbeddingClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LangChain4jEmbeddingClientTest {

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
    void mapsRealVectorAndVersionedMetadata() {
        stubEmbedding("[0.25,-0.5,0.75]");

        EmbeddingClient.EmbeddingResult result = client(3, 2_000, 100).embed("review policy");

        assertThat(result.provider()).isEqualTo("openai-compatible");
        assertThat(result.model()).isEqualTo("embedding-model");
        assertThat(result.version()).isEqualTo("embedding-model-v1");
        assertThat(result.dimension()).isEqualTo(3);
        assertThat(result.vector()).containsExactly(0.25, -0.5, 0.75);
        server.verify(postRequestedFor(urlEqualTo("/v1/embeddings"))
                .withHeader("Authorization", equalTo("Bearer test-api-key"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("embedding-model")))
                .withRequestBody(matchingJsonPath("$.input", equalTo("review policy"))));
    }

    @Test
    void rejectsEmptyAndOversizedInputBeforeCallingProvider() {
        LangChain4jEmbeddingClient client = client(3, 2_000, 8);

        assertThatThrownBy(() -> client.embed(" "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> client.embed("123456789"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("maximum");
        server.verify(0, postRequestedFor(urlEqualTo("/v1/embeddings")));
    }

    @Test
    void rejectsWrongDimensionAndNonFiniteValues() {
        stubEmbedding("[0.1,0.2]");
        assertThatThrownBy(() -> client(3, 2_000, 100).embed("dimension"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("dimension");

        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("non-finite")).thenReturn(Response.from(
                Embedding.from(new float[]{1.0f, Float.NaN, 2.0f})
        ));
        LangChain4jEmbeddingClient client = new LangChain4jEmbeddingClient(
                model,
                "openai-compatible",
                "embedding-model",
                "embedding-model-v1",
                3,
                100,
                ObservationRegistry.NOOP
        );

        assertThatThrownBy(() -> client.embed("non-finite"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("finite");
    }

    @Test
    void classifiesProviderFailuresWithoutLeakingBody() {
        for (int status : new int[]{401, 429, 503}) {
            server.resetAll();
            server.stubFor(post(urlEqualTo("/v1/embeddings"))
                    .willReturn(aResponse().withStatus(status).withBody("secret-provider-detail")));

            if (status == 401) {
                assertThatThrownBy(() -> client(3, 2_000, 100).embed("policy"))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("permanent")
                        .hasMessageNotContaining("secret-provider-detail");
            } else {
                assertThatThrownBy(() -> client(3, 2_000, 100).embed("policy"))
                        .isInstanceOf(AiCallTransientException.class)
                        .hasMessageContaining("transient")
                        .hasMessageNotContaining("secret-provider-detail");
            }
        }
    }

    @Test
    void classifiesTimeoutAsTransientAndMalformedResponseAsPermanent() {
        server.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(aResponse().withFixedDelay(250).withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(success("[0.1,0.2,0.3]"))));
        assertThatThrownBy(() -> client(3, 50, 100).embed("timeout"))
                .isInstanceOf(AiCallTransientException.class);

        server.resetAll();
        server.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("not-json")));
        assertThatThrownBy(() -> client(3, 2_000, 100).embed("malformed"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid response")
                .hasMessageNotContaining("not-json");
    }

    private LangChain4jEmbeddingClient client(int expectedDimension, int timeoutMs, int maxInputChars) {
        Duration timeout = Duration.ofMillis(timeoutMs);
        EmbeddingModel model = OpenAiEmbeddingModel.builder()
                .baseUrl(server.baseUrl() + "/v1")
                .apiKey("test-api-key")
                .modelName("embedding-model")
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .connectTimeout(Duration.ofSeconds(1))
                        .readTimeout(timeout))
                .timeout(timeout)
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                .build();
        return new LangChain4jEmbeddingClient(
                model,
                "openai-compatible",
                "embedding-model",
                "embedding-model-v1",
                expectedDimension,
                maxInputChars,
                ObservationRegistry.NOOP
        );
    }

    private void stubEmbedding(String vector) {
        server.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(success(vector))));
    }

    private String success(String vector) {
        return "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\","
                + "\"index\":0,\"embedding\":" + vector + "}],"
                + "\"model\":\"embedding-model\",\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2}}";
    }
}
