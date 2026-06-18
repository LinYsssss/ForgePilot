package com.example.codereview.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces the active AI review provider in {@code /actuator/health} (details shown only to
 * authenticated callers). Reports the provider and chat model, and — for {@code openai-compatible}
 * — whether the base URL and API key are configured. The API key value itself is never exposed.
 */
@Component("aiProvider")
public class AiProviderHealthIndicator implements HealthIndicator {

    private final String provider;
    private final String chatModel;
    private final String baseUrl;
    private final String apiKey;

    public AiProviderHealthIndicator(
            @Value("${app.ai.provider:mock}") String provider,
            @Value("${app.ai.chat-model:}") String chatModel,
            @Value("${app.ai.base-url:}") String baseUrl,
            @Value("${app.ai.api-key:}") String apiKey) {
        this.provider = provider;
        this.chatModel = chatModel;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up()
                .withDetail("provider", provider)
                .withDetail("chatModel", chatModel);
        if ("openai-compatible".equals(provider)) {
            boolean configured = isPresent(baseUrl) && isPresent(apiKey);
            builder.withDetail("baseUrl", baseUrl).withDetail("configured", configured);
            if (!configured) {
                return builder.down().withDetail("reason", "缺少 LLM_BASE_URL 或 LLM_API_KEY").build();
            }
        }
        return builder.build();
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
