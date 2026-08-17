package com.example.codereview.assistant;

import com.example.codereview.ai.TokenUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@ConditionalOnProperty(prefix = "app.ai", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleAssistantModelClient implements AssistantModelClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final double temperature;

    public OpenAiCompatibleAssistantModelClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${app.ai.base-url}") String baseUrl,
            @Value("${app.ai.api-key}") String apiKey,
            @Value("${app.ai.chat-model}") String model,
            @Value("${app.ai.temperature:0.0}") double temperature,
            @Value("${app.http.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${app.ai.read-timeout-ms:300000}") int readTimeoutMs) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("AI_PROVIDER=openai-compatible requires LLM_BASE_URL and LLM_API_KEY");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restClient = builder.baseUrl(baseUrl.replaceAll("/+$", ""))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(factory).build();
        this.objectMapper = objectMapper;
        this.model = model;
        this.temperature = temperature;
    }

    @Override
    public TokenUsage stream(AssistantPrompt prompt, Consumer<String> onDelta, BooleanSupplier cancelled) {
        Map<String, Object> request = Map.of(
                "model", model,
                "temperature", temperature,
                "stream", true,
                "stream_options", Map.of("include_usage", true),
                "messages", List.of(
                        Map.of("role", "system", "content", prompt.systemMessage()),
                        Map.of("role", "user", "content", prompt.userMessage())));
        return restClient.post().uri("/chat/completions").body(request).exchange((httpRequest, response) -> {
            if (response.getStatusCode().isError()) {
                throw new IllegalStateException("AI stream request failed with status " + response.getStatusCode().value());
            }
            OpenAiSseParser parser = new OpenAiSseParser(objectMapper, text -> {
                if (!cancelled.getAsBoolean()) {
                    onDelta.accept(text);
                }
            });
            try (InputStream input = response.getBody();
                 InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                char[] chars = new char[2048];
                int count;
                while (!cancelled.getAsBoolean() && (count = reader.read(chars)) >= 0) {
                    if (count > 0) {
                        parser.feed(new String(chars, 0, count));
                    }
                }
                parser.finish();
                return parser.usage();
            }
        });
    }
}
