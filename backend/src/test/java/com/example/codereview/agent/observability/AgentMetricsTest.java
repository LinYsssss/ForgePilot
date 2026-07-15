package com.example.codereview.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.agent.run.AgentRunStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class AgentMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AgentMetrics metrics = new AgentMetrics(registry);

    @Test
    void runLifecycleCountersUseBoundedEventTag() {
        metrics.runCreated();
        metrics.runCompleted();
        metrics.runFailed();
        metrics.runsRecovered(3);

        assertThat(runCount("created")).isEqualTo(1);
        assertThat(runCount("completed")).isEqualTo(1);
        assertThat(runCount("failed")).isEqualTo(1);
        assertThat(runCount("recovered")).isEqualTo(3);
    }

    @Test
    void recoveredCounterIgnoresNonPositiveCounts() {
        metrics.runsRecovered(0);
        metrics.runsRecovered(-2);

        assertThat(registry.find("reposage.agent.runs").tag("event", "recovered").counter()).isNull();
    }

    @Test
    void stepTimerIsTaggedByTypeAndOutcome() {
        metrics.recordStep(AgentRunStatus.EXECUTING_TOOLS, "SUCCEEDED", 12);

        var timer = registry.get("reposage.agent.step")
                .tag("type", "EXECUTING_TOOLS")
                .tag("outcome", "succeeded")
                .timer();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void toolTimerIsTaggedByToolAndStatus() {
        metrics.recordTool("git.diff", true, 5);
        metrics.recordTool("git.diff", false, 7);

        assertThat(registry.get("reposage.agent.tool").tag("tool", "git.diff").tag("status", "success").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("reposage.agent.tool").tag("tool", "git.diff").tag("status", "failure").timer().count())
                .isEqualTo(1);
    }

    private double runCount(String event) {
        return registry.get("reposage.agent.runs").tag("event", event).counter().count();
    }
}
