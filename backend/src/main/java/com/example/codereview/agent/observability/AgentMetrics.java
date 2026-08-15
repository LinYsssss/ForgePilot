package com.example.codereview.agent.observability;

import com.example.codereview.agent.run.AgentRunStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Agent 控制面的 Micrometer 指标,经 {@code /actuator/prometheus} 导出。
 *
 * <p>指标只打**有界、低基数**的标签——run 生命周期事件、步骤类型、工具名、结果/状态。
 * 无界标识(run id、仓库名、错误信息)刻意永不作为标签,因为每一个不同的标签值都会新开一条
 * 时间序列。逐 run 的审计留痕由数据库负责;这些指标提供的是便于抓取的聚合视图。
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

    /** 一个 run 进入了流水线(首次离开 {@code RECEIVED} 的流转)。 */
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

    /** 记录一次步骤执行尝试:它声明的类型与最终结果。 */
    public void recordStep(AgentRunStatus stepType, String outcome, long millis) {
        Timer.builder(STEP_TIMER)
                .description("Agent step execution latency")
                .tag("type", stepType.name())
                .tag("outcome", outcome.toLowerCase(Locale.ROOT))
                .register(registry)
                .record(Math.max(0, millis), TimeUnit.MILLISECONDS);
    }

    /** 记录一次工具调用:工具名与成功/失败。 */
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
