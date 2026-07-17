package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.orchestration.AgentAnalysisContextRepository;
import com.example.codereview.agent.orchestration.AgentChangeAnalysisCheckpoint;
import com.example.codereview.agent.orchestration.AgentRetrievedContextCheckpoint;
import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.orchestration.AgentStepExecutor;
import com.example.codereview.agent.orchestration.AgentStepResult;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.ai.langchain4j.LangChain4jReviewContentRetriever;
import com.example.codereview.context.ReviewRetrievalQuery;
import com.example.codereview.language.ToolCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.content.ContentMetadata;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class RetrievingContextStepExecutor implements AgentStepExecutor {

    private final AgentAnalysisContextRepository contexts;
    private final LangChain4jReviewContentRetriever retriever;
    private final ObjectMapper mapper;

    @Autowired
    public RetrievingContextStepExecutor(
            AgentAnalysisContextRepository contexts,
            LangChain4jReviewContentRetriever retriever,
            ObjectMapper mapper
    ) {
        this.contexts = contexts;
        this.retriever = retriever;
        this.mapper = mapper;
    }

    public RetrievingContextStepExecutor() {
        this.contexts = null;
        this.retriever = null;
        this.mapper = null;
    }

    @Override
    public AgentRunStatus state() {
        return AgentRunStatus.RETRIEVING_CONTEXT;
    }

    @Override
    public AgentStepResult execute(AgentStepExecutionContext context) {
        if (context.stepType() != state()) {
            throw new IllegalArgumentException("Executor state does not match context");
        }
        if (contexts == null) {
            return AgentStepResult.checkpoint(state());
        }
        var analysisContext = contexts.findByAgentRunId(context.agentRunId())
                .orElseThrow(() -> new AgentStepExecutionException(
                        AgentFailureType.ENVIRONMENT_INCOMPLETE, "Change analysis checkpoint is missing"
                ));
        analysisContext.requireHead(context.headSha());
        try {
            AgentChangeAnalysisCheckpoint change = mapper.readValue(
                    analysisContext.getChangeSetJson(), AgentChangeAnalysisCheckpoint.class
            );
            List<String> paths = change.changeSet().files().stream().map(file -> file.path()).toList();
            List<String> ruleIds = change.analyses().stream()
                    .flatMap(analysis -> analysis.commands().stream())
                    .map(ToolCommand::commandId)
                    .distinct()
                    .toList();
            ReviewRetrievalQuery scope = new ReviewRetrievalQuery(
                    context.projectId(), List.of(), context.headSha(), 65_536, 0.35, 12,
                    paths, List.of(), List.of(), List.of(), List.of(), ruleIds
            );
            List<AgentRetrievedContextCheckpoint.Evidence> evidence = retriever.retrieve(
                    LangChain4jReviewContentRetriever.toQuery(scope)
            ).stream().map(content -> new AgentRetrievedContextCheckpoint.Evidence(
                    content.textSegment().text(),
                    content.textSegment().metadata().getString("citation"),
                    content.textSegment().metadata().getString("source_name"),
                    content.textSegment().metadata().getInteger("chunk_index"),
                    content.textSegment().metadata().getString("document_type"),
                    content.textSegment().metadata().getString("source_version"),
                    ((Number) content.metadata().get(ContentMetadata.SCORE)).doubleValue(),
                    "untrusted".equals(content.textSegment().metadata().getString("trust"))
            )).toList();
            analysisContext.contextRetrieved(mapper.writeValueAsString(
                    new AgentRetrievedContextCheckpoint("agent-retrieved-context-v1", evidence)
            ));
            contexts.save(analysisContext);
            return new AgentStepResult(
                    "agent-step-result-v1", state(), AgentStepResult.Disposition.ADVANCE,
                    AgentRunStatus.VERIFYING_FINDINGS,
                    Map.of("retrievedEvidence", evidence.size(), "toolRuleIds", ruleIds.size())
            );
        } catch (JsonProcessingException ex) {
            throw new AgentStepExecutionException(
                    AgentFailureType.INTERNAL_ERROR, "Retrieved context checkpoint is invalid", ex
            );
        }
    }
}
