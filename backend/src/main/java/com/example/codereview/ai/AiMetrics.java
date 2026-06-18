package com.example.codereview.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Micrometer metrics for AI review calls, exported via {@code /actuator/prometheus}.
 * Meters are tagged by provider + model + status so a Grafana dashboard can break down
 * throughput, latency and token spend per model. The DB {@code ai_call_log} keeps the
 * per-call audit trail; these meters are the aggregate, scrape-friendly view.
 */
@Component
public class AiMetrics {

    private static final String REVIEW_TIMER = "reposage.ai.review";
    private static final String TOKEN_COUNTER = "reposage.ai.tokens";

    private final MeterRegistry registry;
    private final String provider;
    private final String model;

    public AiMetrics(MeterRegistry registry,
                     @Value("${app.ai.provider}") String provider,
                     @Value("${app.ai.chat-model}") String model) {
        this.registry = registry;
        this.provider = provider;
        this.model = model;
    }

    public void recordSuccess(long latencyMs, TokenUsage usage) {
        recordLatency("success", latencyMs);
        TokenUsage u = usage == null ? TokenUsage.none() : usage;
        if (u.totalTokens() > 0) {
            tokenCounter("prompt").increment(u.promptTokens());
            tokenCounter("completion").increment(u.completionTokens());
            tokenCounter("total").increment(u.totalTokens());
        }
    }

    public void recordFailure(long latencyMs) {
        recordLatency("failure", latencyMs);
    }

    private void recordLatency(String status, long latencyMs) {
        Timer.builder(REVIEW_TIMER)
                .description("AI code review chat call latency")
                .tag("provider", provider)
                .tag("model", model)
                .tag("status", status)
                .register(registry)
                .record(Math.max(0, latencyMs), TimeUnit.MILLISECONDS);
    }

    private io.micrometer.core.instrument.Counter tokenCounter(String type) {
        return registry.counter(TOKEN_COUNTER, "provider", provider, "model", model, "type", type);
    }
}
