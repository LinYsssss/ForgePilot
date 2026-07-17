package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.orchestration.AgentAnalysisContext;
import com.example.codereview.agent.orchestration.AgentAnalysisContextRepository;
import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.orchestration.AgentStepExecutor;
import com.example.codereview.agent.orchestration.AgentStepResult;
import com.example.codereview.agent.orchestration.RepositoryArchiveRefResolver;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.tool.AgentToolRegistry;
import com.example.codereview.agent.tool.ToolContext;
import com.example.codereview.agent.tool.ToolResult;
import com.example.codereview.language.RepositoryProfile;
import com.example.codereview.pullrequest.PullRequestEntity;
import com.example.codereview.pullrequest.PullRequestRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class PreparingRepositoryStepExecutor implements AgentStepExecutor {

    private final PullRequestRepository pullRequests;
    private final AgentToolRegistry tools;
    private final AgentAnalysisContextRepository contexts;
    private final RepositoryArchiveRefResolver archives;
    private final ObjectMapper mapper;

    @Autowired
    public PreparingRepositoryStepExecutor(
            PullRequestRepository pullRequests,
            AgentToolRegistry tools,
            AgentAnalysisContextRepository contexts,
            RepositoryArchiveRefResolver archives,
            ObjectMapper mapper
    ) {
        this.pullRequests = pullRequests;
        this.tools = tools;
        this.contexts = contexts;
        this.archives = archives;
        this.mapper = mapper;
    }

    public PreparingRepositoryStepExecutor() {
        this.pullRequests = null;
        this.tools = null;
        this.contexts = null;
        this.archives = null;
        this.mapper = null;
    }

    @Override
    public AgentRunStatus state() {
        return AgentRunStatus.PREPARING_REPOSITORY;
    }

    @Override
    public AgentStepResult execute(AgentStepExecutionContext context) {
        if (context.stepType() != state()) {
            throw new IllegalArgumentException("Executor state does not match context");
        }
        if (pullRequests == null) {
            return AgentStepResult.checkpoint(state());
        }
        PullRequestEntity pullRequest = pullRequests.findById(context.pullRequestId())
                .orElseThrow(() -> new AgentStepExecutionException(
                        AgentFailureType.ENVIRONMENT_INCOMPLETE, "Persisted pull request is missing"
                ));
        if (!context.headSha().equals(pullRequest.getHeadSha())) {
            throw new AgentStepExecutionException(
                    AgentFailureType.SECURITY_VIOLATION, "Pull request head SHA changed before preparation"
            );
        }
        String archiveRef = archives.resolve(context.agentRunId(), context.headSha());
        JsonNode input = mapper.createObjectNode()
                .put("archiveRef", archiveRef)
                .put("baseRef", pullRequest.getBaseSha())
                .put("headRef", pullRequest.getHeadSha())
                .put("maxBytes", 131_072);
        ToolResult<?> result = tools.execute(
                "git.diff",
                input,
                new ToolContext(
                        context.agentRunId(), context.agentStepId(),
                        "prepare:" + context.agentRunId() + ":git.diff:" + context.headSha(),
                        false, context.traceId()
                )
        );
        if (!result.success()) {
            throw new AgentStepExecutionException(
                    AgentFailureType.ENVIRONMENT_INCOMPLETE,
                    result.error() == null ? "Prepared repository diff is unavailable" : result.error()
            );
        }
        String diff = output(result.data());
        List<String> paths = changedPaths(diff);
        RepositoryProfile profile = RepositoryProfile.fromPaths(paths);
        AgentAnalysisContext analysis = contexts.findByAgentRunId(context.agentRunId())
                .orElseGet(() -> new AgentAnalysisContext(context.agentRunId(), context.headSha()));
        analysis.requireHead(context.headSha());
        try {
            analysis.repositoryPrepared(
                    archiveRef, pullRequest.getBaseSha(), mapper.writeValueAsString(profile), diff
            );
            contexts.save(analysis);
        } catch (JsonProcessingException ex) {
            throw new AgentStepExecutionException(
                    AgentFailureType.INTERNAL_ERROR, "Repository profile is not serializable", ex
            );
        }
        return new AgentStepResult(
                "agent-step-result-v1", state(), AgentStepResult.Disposition.ADVANCE,
                AgentRunStatus.ANALYZING_CHANGE,
                Map.of("changedPaths", paths.size(), "languages", profile.languages().size())
        );
    }

    private String output(Object data) {
        JsonNode node = mapper.valueToTree(data);
        JsonNode output = node.get("output");
        if (output == null || !output.isTextual()) {
            throw new AgentStepExecutionException(
                    AgentFailureType.ENVIRONMENT_INCOMPLETE, "Sandbox diff output is missing"
            );
        }
        return output.asText();
    }

    static List<String> changedPaths(String diff) {
        return diff.lines()
                .filter(line -> line.startsWith("+++ "))
                .map(line -> line.substring(4).trim())
                .filter(path -> !"/dev/null".equals(path))
                .map(path -> path.startsWith("b/") ? path.substring(2) : path)
                .filter(path -> !path.isBlank())
                .distinct()
                .toList();
    }
}
