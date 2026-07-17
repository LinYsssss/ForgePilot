package com.example.codereview.ai.langchain4j;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.codereview.ai.AiCallLogService;
import com.example.codereview.knowledge.KnowledgeChunk;
import com.example.codereview.knowledge.KnowledgeChunkRepository;
import com.example.codereview.knowledge.KnowledgeDocumentRepository;
import com.example.codereview.knowledge.KnowledgeService;
import com.example.codereview.project.ProjectService;
import com.example.codereview.rag.EmbeddingClient;
import com.example.codereview.rag.EmbeddingJson;
import com.example.codereview.rag.NoopVectorIndexService;
import com.example.codereview.rag.RagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

class LangChain4jEmbeddingRagIntegrationTest {

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
    void indexesAndRetrievesKnownVectorsThroughTheRealProviderAdapter() {
        server.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":[{\"index\":0,\"embedding\":[1,0,0]}],"
                                + "\"model\":\"embedding-model\"}")));
        EmbeddingClient embeddings = embeddingClient();
        EmbeddingJson embeddingJson = new EmbeddingJson(new ObjectMapper());
        List<KnowledgeChunk> stored = new ArrayList<>();
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        when(chunks.save(any())).thenAnswer(invocation -> {
            KnowledgeChunk chunk = invocation.getArgument(0);
            ReflectionTestUtils.setField(chunk, "id", (long) stored.size() + 1);
            stored.add(chunk);
            return chunk;
        });
        when(chunks.findByProjectId(7L)).thenAnswer(invocation -> List.copyOf(stored));
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        when(documents.save(any())).thenAnswer(invocation -> {
            Object document = invocation.getArgument(0);
            ReflectionTestUtils.setField(document, "id", 10L);
            return document;
        });
        AiCallLogService calls = mock(AiCallLogService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> jdbc = mock(ObjectProvider.class);
        when(jdbc.getIfAvailable()).thenReturn(null);
        RagService rag = new RagService(
                chunks, embeddings, embeddingJson, jdbc, calls,
                "memory", 5, false, 6_000
        );
        KnowledgeService knowledge = new KnowledgeService(
                mock(ProjectService.class),
                documents,
                chunks,
                rag,
                embeddings,
                embeddingJson,
                new NoopVectorIndexService(),
                calls,
                mock(PlatformTransactionManager.class),
                400,
                20,
                false
        );

        knowledge.upload(7L, 9L, "SECURITY", new MockMultipartFile(
                "file", "policy.md", "text/markdown", "security policy".getBytes()
        ));
        var matches = rag.search(7L, "security policy", 5);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).sourceName()).isEqualTo("policy.md");
        assertThat(matches.get(0).score()).isEqualTo(1.0);
        server.verify(2, postRequestedFor(urlEqualTo("/v1/embeddings")));
    }

    private EmbeddingClient embeddingClient() {
        Duration timeout = Duration.ofSeconds(2);
        var model = OpenAiEmbeddingModel.builder()
                .baseUrl(server.baseUrl() + "/v1")
                .apiKey("test-api-key")
                .modelName("embedding-model")
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .connectTimeout(timeout)
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
                3,
                1_000,
                ObservationRegistry.NOOP
        );
    }
}
