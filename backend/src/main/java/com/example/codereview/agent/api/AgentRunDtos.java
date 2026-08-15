package com.example.codereview.agent.api;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.run.AgentStep;
import com.example.codereview.agent.run.AgentStepStatus;
import java.time.Instant;
import java.util.List;

/**
 * Agent Run 时间线 API 的读模型。数据库是唯一真源;这些视图由已持久化的
 * {@link AgentRun}/{@link AgentStep} 行投影而来,同时也通过 SSE 下发。
 */
public final class AgentRunDtos {

    private AgentRunDtos() {
    }

    public record AgentRunDetail(
            Long id,
            Long projectId,
            Long repositoryId,
            Long pullRequestId,
            String headSha,
            AgentRunStatus status,
            int currentStepSequence,
            boolean cancellationRequested,
            boolean terminal,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static AgentRunDetail from(AgentRun run, boolean terminal) {
            return new AgentRunDetail(
                    run.getId(),
                    run.getProjectId(),
                    run.getRepositoryId(),
                    run.getPullRequestId(),
                    run.getHeadSha(),
                    run.getStatus(),
                    run.getCurrentStepSequence(),
                    run.isCancellationRequested(),
                    terminal,
                    run.getCreatedAt(),
                    run.getUpdatedAt()
            );
        }
    }

    public record AgentStepView(
            Long id,
            Long agentRunId,
            int sequenceNo,
            AgentRunStatus stepType,
            AgentStepStatus status,
            int attempt,
            String inputSummary,
            String outputSummary,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            Instant updatedAt
    ) {
        public static AgentStepView from(AgentStep step) {
            return new AgentStepView(
                    step.getId(),
                    step.getAgentRunId(),
                    step.getSequenceNo(),
                    step.getStepType(),
                    step.getStatus(),
                    step.getAttempt(),
                    step.getInputSummary(),
                    step.getOutputSummary(),
                    step.getErrorMessage(),
                    step.getStartedAt(),
                    step.getFinishedAt(),
                    step.getUpdatedAt()
            );
        }
    }

    public record AgentRunTimeline(AgentRunDetail run, List<AgentStepView> steps) {
    }
}
