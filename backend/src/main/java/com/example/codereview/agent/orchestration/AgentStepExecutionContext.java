package com.example.codereview.agent.orchestration;

import com.example.codereview.agent.run.AgentRunStatus;
import java.util.Objects;

public record AgentStepExecutionContext(
        Long agentRunId,
        Long projectId,
        Long repositoryId,
        Long pullRequestId,
        String headSha,
        AgentRunStatus stepType,
        int sequenceNo,
        int attempt,
        String traceId,
        boolean cancellationRequested
) {
    public AgentStepExecutionContext {
        Objects.requireNonNull(agentRunId, "agentRunId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(repositoryId, "repositoryId");
        Objects.requireNonNull(headSha, "headSha");
        Objects.requireNonNull(stepType, "stepType");
        if (sequenceNo <= 0 || attempt < 0) {
            throw new IllegalArgumentException("invalid Agent step identity");
        }
    }
}
