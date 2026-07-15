package com.example.codereview.agent.observability;

import com.example.codereview.agent.run.AgentRunStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Micrometer metrics for the Agent control plane, exported via {@code /actuator/prometheus}.
 *
 * <p>Meters are tagged only with bounded, low-cardinality values — run lifecycle event, step type,
 * tool name, and outcome/status. Unbounded identifiers (run ids, repository names, error messages)
 * are deliberately never used as tags, since each distinct tag value creates a new time series. The
 * database keeps the per-run audit trail; these meters are the aggregate, scrape-friendly view.
 */
@Component
public class AgentMetrics {

    private static final String RUN_COUNTER = "reposage.agent.runs";
    private static final String STEP_TIMER = "reposage.agent.step";
    private static final String TOOL_TIMER = "reposage.agent.tool";

    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A run entered the pipeline (first transition out of {@code RECEIVED}). */
    public void runCreated() {
        runCounter("created").increment();
    }

    public void runCompleted() {
        runCounter("completed").increment();
    }

    public void runFailed() {
        runCounter("failed").increment();
    }

    public void runsRecovered(int count) {
        if (count > 0) {
            runCounter("recovered").increment(count);
        }
    }

    /** Record one step-execution attempt: its declared type and the resulting outcome. */
    public void recordStep(AgentRunStatus stepType, String outcome, long millis) {
        Timer.builder(STEP_TIMER)
                .description("Agent step execution latency")
                .tag("type", stepType.name())
                .tag("outcome", outcome.toLowerCase(Locale.ROOT))
                .register(registry)
                .record(Math.max(0, millis), TimeUnit.MILLISECONDS);
    }

    /** Record one tool invocation: the tool name and success/failure. */
    public void recordTool(String toolName, boolean success, long millis) {
        Timer.builder(TOOL_TIMER)
                .description("Agent tool invocation latency")
                .tag("tool", toolName)
                .tag("status", success ? "success" : "failure")
                .register(registry)
                .record(Math.max(0, millis), TimeUnit.MILLISECONDS);
    }

    private Counter runCounter(String event) {
        return registry.counter(RUN_COUNTER, "event", event);
    }
}
