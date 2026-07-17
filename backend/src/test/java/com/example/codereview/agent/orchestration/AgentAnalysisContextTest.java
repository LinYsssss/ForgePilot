package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AgentAnalysisContextTest {

    @Test
    void persistsVersionedHeadBoundCheckpointsInOrder() {
        AgentAnalysisContext context = new AgentAnalysisContext(1L, "head-1");

        context.repositoryPrepared(
                "workspace://archive-1", "base-1", "{\"languages\":[\"JAVA\"]}", "diff"
        );
        context.changeAnalyzed("{\"baseSha\":\"base\",\"headSha\":\"head-1\"}");
        context.contextRetrieved("[{\"reference\":\"rule#1\"}]");
        context.findingsVerified("[]", "{\"blocking\":false}");

        assertThat(context.getSchemaVersion()).isEqualTo("agent-analysis-v1");
        assertThat(context.getHeadSha()).isEqualTo("head-1");
        assertThat(context.getArchiveRef()).isEqualTo("workspace://archive-1");
        assertThat(context.getGateJson()).contains("false");
    }

    @Test
    void rejectsOutOfOrderCrossHeadAndOversizedState() {
        AgentAnalysisContext context = new AgentAnalysisContext(1L, "head-1");

        assertThatThrownBy(() -> context.changeAnalyzed("{}"))
                .hasMessageContaining("repository");
        assertThatThrownBy(() -> context.requireHead("head-2"))
                .hasMessageContaining("head SHA");
        assertThatThrownBy(() -> context.repositoryPrepared(
                "workspace://archive", "base", "x".repeat(300_000), "diff"
        )).hasMessageContaining("bytes");
    }
}
