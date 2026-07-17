package com.example.codereview.agent.orchestration;

import com.example.codereview.agent.run.AgentRunStatus;
import java.util.Map;
import java.util.Objects;

public record AgentStepResult(
        String version,
        AgentRunStatus state,
        Disposition disposition,
        AgentRunStatus nextState,
        Map<String, Object> output
) {
    public AgentStepResult {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("result version is required");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(disposition, "disposition");
        output = output == null ? Map.of() : Map.copyOf(output);
    }

    public static AgentStepResult checkpoint(AgentRunStatus state) {
        return new AgentStepResult(
                "agent-step-result-v1",
                state,
                Disposition.CHECKPOINTED,
                null,
                Map.of("checkpoint", "typed-executor-ready", "state", state.name())
        );
    }

    public enum Disposition {
        CHECKPOINTED,
        ADVANCE,
        WAITING_EXTERNAL,
        TERMINAL
    }
}
