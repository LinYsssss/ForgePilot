package com.example.codereview.rag;

import com.example.codereview.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@ConditionalOnProperty(prefix = "app.ai", name = "embedding-provider", havingValue = "openai-compatible")
@ConditionalOnProperty(prefix = "app.ai", name = "runtime", havingValue = "legacy", matchIfMissing = true)
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final String version;

    public OpenAiCompatibleEmbeddingClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${app.ai.embedding-base-url}") String baseUrl,
            @Value("${app.ai.embedding-api-key}") String apiKey,
            @Value("${app.ai.embedding-model}") String model,
            @Value("${app.ai.embedding-version:${app.ai.embedding-model}}") String version
    ) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("EMBEDDING_PROVIDER=openai-compatible requires EMBEDDING_BASE_URL and EMBEDDING_API_KEY");
        }
        this.restClient = restClientBuilder
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
        this.model = model;
        this.version = version;
    }

    @Override
    public EmbeddingDescriptor descriptor() {
        return new EmbeddingDescriptor("openai-compatible", model, version, 0);
    }

    @Override
    public EmbeddingResult embed(String text) {
        try {
            Map<String, Object> request = Map.of("model", model, "input", text == null ? "" : text);
            String response = restClient.post()
                    .uri("/embeddings")
                    .body(request)
                    .retrieve()
                    .body(String.class);
            JsonNode embedding = objectMapper.readTree(response).path("data").path(0).path("embedding");
            List<Double> values = new ArrayList<>();
            for (JsonNode node : embedding) {
                values.add(node.asDouble());
            }
            return new EmbeddingResult(
                    "openai-compatible",
                    model,
                    version,
                    values.size(),
                    values
            );
        } catch (Exception ex) {
            throw new BusinessException(6003, "Embedding provider call failed");
        }
    }
}
