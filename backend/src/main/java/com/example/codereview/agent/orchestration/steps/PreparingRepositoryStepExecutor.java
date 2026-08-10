package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.orchestration.AgentAnalysisContext;
import com.example.codereview.agent.orchestration.AgentAnalysisContextRepository;
import com.example.codereview.agent.orchestration.AgentScmContext;
import com.example.codereview.agent.orchestration.AgentScmContextRepository;
import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.orchestration.AgentStepExecutor;
import com.example.codereview.agent.orchestration.AgentStepResult;
import com.example.codereview.agent.orchestration.WorkspaceArchiveService;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.tool.AgentToolRegistry;
import com.example.codereview.agent.tool.ToolContext;
import com.example.codereview.agent.tool.ToolResult;
import com.example.codereview.common.exception.BusinessException;
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
    private final AgentScmContextRepository scmContexts;
    private final AgentToolRegistry tools;
    private final AgentAnalysisContextRepository contexts;
    private final WorkspaceArchiveService workspaceArchives;
    private final ObjectMapper mapper;

    @Autowired
    public PreparingRepositoryStepExecutor(
            PullRequestRepository pullRequests,
            AgentScmContextRepository scmContexts,
            AgentToolRegistry tools,
            AgentAnalysisContextRepository contexts,
            WorkspaceArchiveService workspaceArchives,
            ObjectMapper mapper
    ) {
        this.pullRequests = pullRequests;
        this.scmContexts = scmContexts;
        this.tools = tools;
        this.contexts = contexts;
        this.workspaceArchives = workspaceArchives;
        this.mapper = mapper;
    }

    public PreparingRepositoryStepExecutor() {
        this.pullRequests = null;
        this.scmContexts = null;
        this.tools = null;
        this.contexts = null;
        this.workspaceArchives = null;
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
        // PR 的 base/head 有两个权威来源:UI/旧流程持久化 PullRequestEntity(run 带 pullRequestId);
        // webhook 流程不建 PR 实体,数据在 AgentScmContext(run 的 pullRequestId 为 null)。
        // 此前这里只认前者,webhook 触发的 Run 在首步就死于 findById(null)——链路从未跑通的第三截。
        BaseHead shas = resolveBaseHead(context);
        if (!context.headSha().equals(shas.headSha())) {
            throw new AgentStepExecutionException(
                    AgentFailureType.SECURITY_VIOLATION, "Pull request head SHA changed before preparation"
            );
        }
        // 先真实产出归档(F-04:此前链路里没有任何写入方),再让沙箱以取证方式读回 diff——
        // 这一来一回同时验证了引用契约与共享卷挂载,是"取证步骤成功"的实际含义。
        String archiveRef;
        try {
            archiveRef = workspaceArchives.produceForAgentRun(
                    context.repositoryId(), context.agentRunId(),
                    shas.baseSha(), shas.headSha()
            );
        } catch (BusinessException | IllegalStateException ex) {
            throw new AgentStepExecutionException(
                    AgentFailureType.ENVIRONMENT_INCOMPLETE,
                    "Workspace archive is unavailable: " + ex.getMessage(), ex
            );
        }
        String diff = readBackPreparedDiff(context, archiveRef, shas);
        List<String> paths = changedPaths(diff);
        RepositoryProfile profile = RepositoryProfile.fromPaths(paths);
        AgentAnalysisContext analysis = contexts.findByAgentRunId(context.agentRunId())
                .orElseGet(() -> new AgentAnalysisContext(context.agentRunId(), context.headSha()));
        analysis.requireHead(context.headSha());
        try {
            analysis.repositoryPrepared(
                    archiveRef, shas.baseSha(), mapper.writeValueAsString(profile), diff
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

    private record BaseHead(String baseSha, String headSha) {
    }

    /** 双源解析:优先持久化 PR 实体(UI/旧流程),否则回退 webhook 落库的 SCM 上下文。 */
    private BaseHead resolveBaseHead(AgentStepExecutionContext context) {
        if (context.pullRequestId() != null) {
            PullRequestEntity pullRequest = pullRequests.findById(context.pullRequestId())
                    .orElseThrow(() -> new AgentStepExecutionException(
                            AgentFailureType.ENVIRONMENT_INCOMPLETE, "Persisted pull request is missing"
                    ));
            return new BaseHead(pullRequest.getBaseSha(), pullRequest.getHeadSha());
        }
        AgentScmContext scm = scmContexts.findByAgentRunId(context.agentRunId())
                .orElseThrow(() -> new AgentStepExecutionException(
                        AgentFailureType.ENVIRONMENT_INCOMPLETE,
                        "SCM context is missing for webhook-triggered run"
                ));
        return new BaseHead(scm.getBaseSha(), scm.getHeadSha());
    }

    /** 经沙箱只读工具读回预置 diff:入参口径与工具校验同源(maxBytes ≤65536),失败即环境不完整。 */
    private String readBackPreparedDiff(AgentStepExecutionContext context, String archiveRef, BaseHead shas) {
        JsonNode input = mapper.createObjectNode()
                .put("archiveRef", archiveRef)
                .put("baseRef", shas.baseSha())
                .put("headRef", shas.headSha())
                // 与工具入参校验(requireMaxBytes ≤65536)及全平台输出预算同一口径;
                // 旧值 131072 超过工具自己的上限,首次真实调用即被 Malformed input 拒绝。
                .put("maxBytes", 65_536);
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
        return output(result.data());
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
