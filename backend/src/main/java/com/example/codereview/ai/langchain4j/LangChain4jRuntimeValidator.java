package com.example.codereview.ai.langchain4j;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public final class LangChain4jRuntimeValidator {

    private static final String OPENAI_COMPATIBLE = "openai-compatible";
    private static final Set<String> PLACEHOLDER_MODELS = Set.of("mock-reviewer", "mock-embedding");

    private final LangChain4jRuntime runtime;
    private final Environment environment;
    private final String provider;
    private final String baseUrl;
    private final String apiKey;
    private final String chatModel;
    private final String embeddingProvider;
    private final String embeddingBaseUrl;
    private final String embeddingApiKey;
    private final String embeddingModel;

    public LangChain4jRuntimeValidator(
            LangChain4jRuntime runtime,
            Environment environment,
            @Value("${app.ai.provider:mock}") String provider,
            @Value("${app.ai.base-url:}") String baseUrl,
            @Value("${app.ai.api-key:}") String apiKey,
            @Value("${app.ai.chat-model:}") String chatModel,
            @Value("${app.ai.embedding-provider:mock}") String embeddingProvider,
            @Value("${app.ai.embedding-base-url:}") String embeddingBaseUrl,
            @Value("${app.ai.embedding-api-key:}") String embeddingApiKey,
            @Value("${app.ai.embedding-model:}") String embeddingModel
    ) {
        this.runtime = runtime;
        this.environment = environment;
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.chatModel = chatModel;
        this.embeddingProvider = embeddingProvider;
        this.embeddingBaseUrl = embeddingBaseUrl;
        this.embeddingApiKey = embeddingApiKey;
        this.embeddingModel = embeddingModel;
    }

    @PostConstruct
    void validate() {
        if (!runtime.isLangChain4j() || !environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        if (!OPENAI_COMPATIBLE.equalsIgnoreCase(provider)) {
            throw new IllegalStateException(
                    "Production langchain4j runtime requires app.ai.provider=openai-compatible"
            );
        }

        List<String> missing = new ArrayList<>();
        requireText("app.ai.base-url", baseUrl, missing);
        requireText("app.ai.api-key", apiKey, missing);
        requireModel("app.ai.chat-model", chatModel, missing);

        if (OPENAI_COMPATIBLE.equalsIgnoreCase(embeddingProvider)) {
            requireText("app.ai.embedding-base-url", embeddingBaseUrl, missing);
            requireText("app.ai.embedding-api-key", embeddingApiKey, missing);
            requireModel("app.ai.embedding-model", embeddingModel, missing);
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Production langchain4j runtime missing required configuration: "
                            + String.join(", ", missing)
            );
        }
    }

    private static void requireText(String property, String value, List<String> missing) {
        if (value == null || value.isBlank()) {
            missing.add(property);
        }
    }

    private static void requireModel(String property, String value, List<String> missing) {
        if (value == null || value.isBlank() || PLACEHOLDER_MODELS.contains(value.trim())) {
            missing.add(property);
        }
    }
}
