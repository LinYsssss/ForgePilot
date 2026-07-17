package com.example.codereview.ai.langchain4j;

import com.example.codereview.agent.model.AgentModelClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.ai", name = "runtime", havingValue = "langchain4j")
public class LangChain4jModelConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "provider", havingValue = "openai-compatible")
    ChatModel langChain4jChatModel(
            @Value("${app.ai.base-url}") String baseUrl,
            @Value("${app.ai.api-key}") String apiKey,
            @Value("${app.ai.chat-model}") String model,
            @Value("${app.ai.temperature:0.0}") double temperature,
            @Value("${app.http.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${app.ai.read-timeout-ms:300000}") int readTimeoutMs
    ) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()
                || model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "LangChain4j chat requires app.ai.base-url, app.ai.api-key and app.ai.chat-model"
            );
        }
        Duration connectTimeout = Duration.ofMillis(Math.max(1, connectTimeoutMs));
        Duration readTimeout = Duration.ofMillis(Math.max(1, readTimeoutMs));
        JdkHttpClientBuilder httpClient = new JdkHttpClientBuilder()
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout);
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .apiKey(apiKey)
                .modelName(model)
                .temperature(temperature)
                .httpClientBuilder(httpClient)
                .timeout(readTimeout)
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "provider", havingValue = "openai-compatible")
    AgentModelClient langChain4jAgentModelClient(
            ChatModel langChain4jChatModel,
            @Value("${app.ai.provider}") String provider,
            @Value("${app.ai.chat-model}") String model,
            ObservationRegistry observationRegistry
    ) {
        return new LangChain4jAgentModelClient(
                langChain4jChatModel,
                provider,
                model,
                observationRegistry
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "embedding-provider", havingValue = "openai-compatible")
    EmbeddingModel langChain4jEmbeddingModel(
            @Value("${app.ai.embedding-base-url}") String baseUrl,
            @Value("${app.ai.embedding-api-key}") String apiKey,
            @Value("${app.ai.embedding-model}") String model,
            @Value("${app.http.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${app.ai.embedding-read-timeout-ms:${app.ai.read-timeout-ms:300000}}") int readTimeoutMs
    ) {
        Duration connectTimeout = Duration.ofMillis(Math.max(1, connectTimeoutMs));
        Duration readTimeout = Duration.ofMillis(Math.max(1, readTimeoutMs));
        return OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .apiKey(apiKey)
                .modelName(model)
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .connectTimeout(connectTimeout)
                        .readTimeout(readTimeout))
                .timeout(readTimeout)
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "embedding-provider", havingValue = "openai-compatible")
    com.example.codereview.rag.EmbeddingClient langChain4jEmbeddingClient(
            EmbeddingModel langChain4jEmbeddingModel,
            @Value("${app.ai.embedding-provider}") String provider,
            @Value("${app.ai.embedding-model}") String model,
            @Value("${app.ai.embedding-version:${app.ai.embedding-model}}") String version,
            @Value("${app.ai.embedding-dimensions:0}") int expectedDimension,
            @Value("${app.ai.embedding-max-input-chars:32000}") int maxInputChars,
            ObservationRegistry observationRegistry
    ) {
        return new LangChain4jEmbeddingClient(
                langChain4jEmbeddingModel,
                provider,
                model,
                version,
                expectedDimension,
                maxInputChars,
                observationRegistry
        );
    }
}
