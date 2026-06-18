package com.example.codereview.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Health of the optional FastAPI risk model service. When disabled (the default) it reports UP with
 * {@code enabled=false} and makes no network call. When enabled it pings {@code /model/status} with
 * a short timeout so a slow or down model service surfaces as DOWN without hanging the probe — and
 * without failing the whole task pipeline, since model scoring is only an auxiliary signal.
 */
@Component("modelService")
public class ModelServiceHealthIndicator implements HealthIndicator {

    private final boolean enabled;
    private final String baseUrl;
    private final RestClient restClient;

    public ModelServiceHealthIndicator(
            @Value("${app.model-service.enabled:false}") boolean enabled,
            @Value("${app.model-service.base-url:}") String baseUrl,
            RestClient.Builder restClientBuilder) {
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.restClient = restClientBuilder.requestFactory(factory).build();
    }

    @Override
    public Health health() {
        if (!enabled) {
            return Health.up().withDetail("enabled", false).build();
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return Health.down().withDetail("enabled", true).withDetail("reason", "缺少 MODEL_SERVICE_URL").build();
        }
        try {
            restClient.get().uri(baseUrl.replaceAll("/+$", "") + "/model/status").retrieve().toBodilessEntity();
            return Health.up().withDetail("enabled", true).withDetail("url", baseUrl).build();
        } catch (Exception ex) {
            return Health.down().withDetail("enabled", true).withDetail("url", baseUrl)
                    .withDetail("error", ex.getClass().getSimpleName()).build();
        }
    }
}
