package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.orchestration.AgentPublicationService;
import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.orchestration.AgentStepExecutor;
import com.example.codereview.agent.orchestration.AgentStepResult;
import com.example.codereview.agent.orchestration.WorkspaceArchiveService;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class PublishingResultStepExecutor implements AgentStepExecutor {

    private final AgentPublicationService publications;
    private final WorkspaceArchiveService workspaceArchives;

    @Autowired
    public PublishingResultStepExecutor(
            AgentPublicationService publications, WorkspaceArchiveService workspaceArchives) {
        this.publications = publications;
        this.workspaceArchives = workspaceArchives;
    }

    public PublishingResultStepExecutor() {
        this.publications = null;
        this.workspaceArchives = null;
    }

    @Override
    public AgentRunStatus state() { return AgentRunStatus.PUBLISHING_RESULT; }

    @Override
    public AgentStepResult execute(AgentStepExecutionContext context) {
        if (context.stepType() != state()) {
            throw new IllegalArgumentException("Executor state does not match context");
        }
        if (publications == null) {
            return AgentStepResult.checkpoint(state());
        }
        var publication = publications.publish(context.agentRunId(), context.headSha());
        // 结果已发布,本 Run 不会再有沙箱调用,共享卷上的归档使命结束,就地清理。
        // cleanupForRun 契约上永不抛出;FAILED/TIMED_OUT 不走这里,归档留给运维重试,由 TTL 清扫兜底。
        workspaceArchives.cleanupForRun(context.agentRunId(), context.headSha());
        return new AgentStepResult(
                "agent-step-result-v1", state(), AgentStepResult.Disposition.ADVANCE,
                AgentRunStatus.COMPLETED,
                Map.of(
                        "publicationId", publication.getId() == null ? 0L : publication.getId(),
                        "publicationStatus", publication.getStatus()
                )
        );
    }
}
