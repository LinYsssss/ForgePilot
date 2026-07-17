package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.orchestration.AgentPublicationService;
import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.orchestration.AgentStepExecutor;
import com.example.codereview.agent.orchestration.AgentStepResult;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class PublishingResultStepExecutor implements AgentStepExecutor {

    private final AgentPublicationService publications;

    @Autowired
    public PublishingResultStepExecutor(AgentPublicationService publications) {
        this.publications = publications;
    }

    public PublishingResultStepExecutor() { this.publications = null; }

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
