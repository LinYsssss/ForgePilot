package com.example.codereview.agent.orchestration;

import com.example.codereview.agent.run.AgentRunStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class AgentStepExecutorRegistry {

    public static final Set<AgentRunStatus> EXECUTABLE_STATES = Set.copyOf(EnumSet.of(
            AgentRunStatus.PREPARING_REPOSITORY,
            AgentRunStatus.ANALYZING_CHANGE,
            AgentRunStatus.PLANNING,
            AgentRunStatus.EXECUTING_TOOLS,
            AgentRunStatus.RETRIEVING_CONTEXT,
            AgentRunStatus.VERIFYING_FINDINGS,
            AgentRunStatus.GENERATING_PATCH,
            AgentRunStatus.VALIDATING_PATCH,
            AgentRunStatus.WAITING_APPROVAL,
            AgentRunStatus.PUBLISHING_RESULT
    ));

    private final Map<AgentRunStatus, AgentStepExecutor> executors;

    public AgentStepExecutorRegistry(List<AgentStepExecutor> candidates) {
        EnumMap<AgentRunStatus, AgentStepExecutor> mapped = new EnumMap<>(AgentRunStatus.class);
        for (AgentStepExecutor executor : candidates) {
            AgentStepExecutor previous = mapped.put(executor.state(), executor);
            if (previous != null) {
                throw new IllegalStateException("Duplicate Agent step executor for " + executor.state());
            }
        }
        if (!mapped.keySet().equals(EXECUTABLE_STATES)) {
            EnumSet<AgentRunStatus> missing = EnumSet.copyOf(EXECUTABLE_STATES);
            missing.removeAll(mapped.keySet());
            EnumSet<AgentRunStatus> unexpected = EnumSet.copyOf(mapped.keySet());
            unexpected.removeAll(EXECUTABLE_STATES);
            throw new IllegalStateException(
                    "Invalid Agent step executor registry; missing=" + missing + ", unexpected=" + unexpected
            );
        }
        this.executors = Map.copyOf(mapped);
    }

    public AgentStepExecutor require(AgentRunStatus state) {
        AgentStepExecutor executor = executors.get(state);
        if (executor == null) {
            throw new IllegalArgumentException("No Agent step executor for " + state);
        }
        return executor;
    }
}
