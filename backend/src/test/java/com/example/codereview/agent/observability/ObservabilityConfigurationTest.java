package com.example.codereview.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ObservabilityConfigurationTest {
    @Test
    void deploymentProvidesOtelPrometheusAndAvoidsUnboundedMetricTags() throws Exception {
        String compose = Files.readString(Path.of("..", "deploy", "docker-compose.yml"));
        assertThat(compose).contains("otel-collector:", "prometheus:", "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT");
        assertThat(Files.readString(Path.of("..", "deploy", "observability", "otel-collector.yml")))
                .contains("traces:", "metrics:", "memory_limiter");
        String metrics = Files.readString(Path.of("src", "main", "java", "com", "example", "codereview",
                "agent", "observability", "AgentMetrics.java"));
        assertThat(metrics).doesNotContain("agentRunId", "jobId", "traceId", "repositoryId", "projectId");
    }
}
