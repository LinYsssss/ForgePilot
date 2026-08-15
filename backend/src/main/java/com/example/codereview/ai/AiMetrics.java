package com.example.codereview.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI 审查调用的 Micrometer 指标,经 {@code /actuator/prometheus} 导出。
 * 指标按 provider + model + status 打标签,好让 Grafana 看板能按模型拆分吞吐、时延与 token 开销。
 * 逐次调用的审计留痕由数据库 {@code ai_call_log} 负责;这些指标提供的是便于抓取的聚合视图。
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
