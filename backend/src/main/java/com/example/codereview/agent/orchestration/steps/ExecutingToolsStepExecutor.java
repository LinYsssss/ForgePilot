package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.model.StructuredModelResponse;
import com.example.codereview.agent.model.AgentModelClient;
import com.example.codereview.agent.model.AgentModelBudgetPolicy;
import com.example.codereview.agent.model.AgentPlanningCheckpoint;
import com.example.codereview.agent.model.ModelOutputValidator;
import com.example.codereview.agent.model.PromptEnvelope;
import com.example.codereview.agent.model.StructuredAgentModelService;
import com.example.codereview.agent.orchestration.AgentAnalysisContext;
import com.example.codereview.agent.orchestration.AgentAnalysisContextRepository;
import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.orchestration.AgentStepExecutor;
import com.example.codereview.agent.orchestration.AgentStepResult;
import com.example.codereview.agent.orchestration.AgentToolLoop;
import com.example.codereview.agent.plan.ReviewPlan;
import com.example.codereview.agent.plan.ReviewPlanRepository;
import com.example.codereview.agent.plan.ReviewPlanValidator;
import com.example.codereview.agent.prompt.AgentPromptAssembler;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.example.codereview.agent.run.AgentRunStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class ExecutingToolsStepExecutor implements AgentStepExecutor {

    private static final Set<String> READ_TOOLS = Set.of("git.diff", "git.file", "code.search");
    /** 与工具入参校验(requireMaxBytes)同一口径的输出上限。 */
    private static final int MAX_TOOL_BYTES = 65_536;

    private final ReviewPlanRepository plans;
    private final AgentToolLoop loop;
    private final ObjectMapper mapper;
    private final StructuredAgentModelService models;
    private final Optional<AgentModelClient> client;
    private final AgentPromptAssembler prompts;
    private final AgentModelBudgetPolicy modelBudget;
    private final AgentAnalysisContextRepository analysisContexts;

    @Autowired
    public ExecutingToolsStepExecutor(
            ReviewPlanRepository plans,
            AgentToolLoop loop,
            ObjectMapper mapper,
            StructuredAgentModelService models,
            Optional<AgentModelClient> client,
            AgentPromptAssembler prompts,
            AgentModelBudgetPolicy modelBudget,
            AgentAnalysisContextRepository analysisContexts
    ) {
        this.plans = plans;
        this.loop = loop;
        this.mapper = mapper;
        this.models = models;
        this.client = client;
        this.prompts = prompts;
        this.modelBudget = modelBudget;
        this.analysisContexts = analysisContexts;
    }

    public ExecutingToolsStepExecutor(
            ReviewPlanRepository plans,
            AgentToolLoop loop,
            ObjectMapper mapper
    ) {
        this(plans, loop, mapper, null, Optional.empty(), null, AgentModelBudgetPolicy.defaults(), null);
    }

    public ExecutingToolsStepExecutor() {
        this.plans = null;
        this.loop = null;
        this.mapper = null;
        this.models = null;
        this.client = Optional.empty();
        this.prompts = null;
        this.modelBudget = null;
        this.analysisContexts = null;
    }

    @Override
    public AgentRunStatus state() {
        return AgentRunStatus.EXECUTING_TOOLS;
    }

    @Override
    public AgentStepResult execute(AgentStepExecutionContext context) {
        if (context.stepType() != state()) {
            throw new IllegalArgumentException("Executor state does not match context");
        }
        if (plans == null) {
            return AgentStepResult.checkpoint(state());
        }
        if (context.cancellationRequested()) {
            throw new AgentStepExecutionException(AgentFailureType.CANCELED, "Agent run canceled before tools");
        }
        ReviewPlan plan = plans.findByAgentRunId(context.agentRunId())
                .orElseThrow(() -> new AgentStepExecutionException(
                        AgentFailureType.INVALID_MODEL_OUTPUT, "Validated review plan is missing"
                ));
        try {
            StructuredModelResponse response = mapper.readValue(
                    plan.getValidatedPlanJson(), StructuredModelResponse.class
            );
            if (analysisContexts == null) {
                return AgentStepResult.checkpoint(state());
            }
            AgentAnalysisContext analysis = analysisContexts.findByAgentRunId(context.agentRunId())
                    .orElseThrow(() -> new AgentStepExecutionException(
                            AgentFailureType.ENVIRONMENT_INCOMPLETE, "Prepared repository checkpoint is missing"
                    ));
            ToolRun tools = runPlannedTools(context, response, analysis);
            Receipt receipt = client.isPresent()
                    ? finalizeReceipt(context, plan, response, tools)
                    : new Receipt(response, new AgentModelBudgetPolicy.UsageSnapshot(
                            0, 0, 0, 0, java.math.BigDecimal.ZERO));
            return new AgentStepResult(
                    "agent-step-result-v1",
                    state(),
                    AgentStepResult.Disposition.ADVANCE,
                    AgentRunStatus.RETRIEVING_CONTEXT,
                    Map.of(
                            "toolRequests", tools.results().size(),
                            "toolSucceeded", tools.succeeded(),
                            "skippedBlindPlanItems", tools.skippedBlind(),
                            "finalPlanItems", receipt.finalPlan().plan().size(),
                            "modelCalls", receipt.totalUsage().modelCalls(),
                            "estimatedCost", receipt.totalUsage().estimatedCost().toPlainString()
                    )
            );
        } catch (JsonProcessingException ex) {
            throw new AgentStepExecutionException(
                    AgentFailureType.INVALID_MODEL_OUTPUT, "Validated review plan JSON is invalid", ex
            );
        }
    }

    /** 计划工具执行的结果与审计计数(跳过数记入步骤输出可审计)。 */
    private record ToolRun(
            List<AgentToolLoop.ToolResultEnvelope> results,
            Set<String> planned,
            long succeeded,
            long skippedBlind
    ) {
    }

    /** 终稿回执:回执校验后的最终计划与累计模型用量。 */
    private record Receipt(StructuredModelResponse finalPlan, AgentModelBudgetPolicy.UsageSnapshot totalUsage) {
    }

    /**
     * 按策略执行已验证计划的工具:基础设施参数服务端权威注入、盲目占位项预检跳过、
     * 任何策略违规(计划外工具、超预算、危险字符)使整步以 SECURITY_VIOLATION 失败。
     */
    private ToolRun runPlannedTools(
            AgentStepExecutionContext context,
            StructuredModelResponse response,
            AgentAnalysisContext analysis
    ) {
        List<AgentToolLoop.ToolRequest> requests = response.plan().stream()
                .map(item -> new AgentToolLoop.ToolRequest(
                        item.modelRequestId(), item.toolName(),
                        authoritativeArguments(item.toolName(), item.arguments(), analysis)
                ))
                .filter(ExecutingToolsStepExecutor::hasRequiredSemanticArguments)
                .toList();
        long skippedBlind = response.plan().size() - requests.size();
        Set<String> planned = response.plan().stream()
                .map(ReviewPlan.PlanItem::toolName)
                .collect(Collectors.toUnmodifiableSet());
        List<AgentToolLoop.ToolResultEnvelope> results = loop.execute(
                new AgentToolLoop.LoopContext(
                        context.agentRunId(), context.agentStepId(), context.traceId(), false
                ),
                requests,
                new AgentToolLoop.LoopPolicy(planned, READ_TOOLS, 8, 16_384),
                context::cancellationRequested
        );
        long succeeded = results.stream()
                .filter(item -> item.status() == AgentToolLoop.ToolStatus.SUCCESS)
                .count();
        long rejected = results.stream()
                .filter(item -> item.status() == AgentToolLoop.ToolStatus.POLICY_REJECTED)
                .count();
        if (rejected > 0) {
            throw new AgentStepExecutionException(
                    AgentFailureType.SECURITY_VIOLATION,
                    "One or more model tool requests were rejected by policy"
            );
        }
        return new ToolRun(results, planned, succeeded, skippedBlind);
    }

    /** 终稿回执的模型交互:提示词组装、回执预算策略、生成校验、终稿计划落库。 */
    private Receipt finalizeReceipt(
            AgentStepExecutionContext context,
            ReviewPlan plan,
            StructuredModelResponse response,
            ToolRun tools
    ) throws JsonProcessingException {
        AgentModelBudgetPolicy.UsageSnapshot totalUsage = previousUsage(plan);
        PromptEnvelope prompt = receiptPrompt(tools.planned(), tools.results());
        // 回执校验的预算必须容纳"逐字回写原计划全部条目"这一提示词要求本身:
        // 此前的 8 - requests.size() 是把"新请求预算"误用于"收据校验",原计划
        // 一旦 ≥5 项,合规回执的条数必超预算,步骤必然失败。回执预算=原计划条数。
        // clampOverBudget 保持关闭:回执是已执行证据的收据,裁剪等于撕毁收据,
        // 宁可硬失败暴露问题。
        ReviewPlanValidator.PlanPolicy policy = new ReviewPlanValidator.PlanPolicy(
                tools.planned(), Set.of(), tools.planned(), Math.max(1, response.plan().size()), true
        );
        StructuredAgentModelService.ModelGenerationResult finalGeneration = models.generateBounded(
                context.agentRunId(), client.orElseThrow(), prompt, false, policy
        );
        totalUsage = modelBudget.requireWithinBudget(totalUsage, finalGeneration);
        ModelOutputValidator.ValidationResult finalResult = finalGeneration.validation();
        if (!finalResult.valid()) {
            throw new AgentStepExecutionException(finalResult.failureType(), finalResult.error());
        }
        StructuredModelResponse finalPlan = finalResult.response();
        plan.accept(mapper.writeValueAsString(finalPlan));
        plans.save(plan);
        return new Receipt(finalPlan, totalUsage);
    }

    /**
     * 终稿计划的 plan 数组是"已执行证据的回执"而非新请求(本步之后无人再执行它),
     * 但校验器拒绝空 plan 且只认注册工具名——首次真实运行 MiMo 自创
     * static_analysis/security_scan 即整步失败,故合法工具名必须白纸黑字枚举,
     * 并要求逐字回写原计划项,不给模型自由发挥的空间。
     */
    private PromptEnvelope receiptPrompt(
            Set<String> planned,
            List<AgentToolLoop.ToolResultEnvelope> results
    ) throws JsonProcessingException {
        return prompts.assemble(new AgentPromptAssembler.Input(
                "review-v1",
                "Use the supplied persisted tool results to return the final schema-valid review plan. "
                        + "Copy the original validated plan items verbatim into \"plan\" (same toolName, "
                        + "arguments, purpose, expectedEvidence and modelRequestId; \"plan\" must not be "
                        + "empty and must not contain new items). The only legal toolName values are: "
                        + planned + ". Any other tool name (for example static_analysis or security_scan) "
                        + "fails validation. Put your actual review conclusions, each backed by the "
                        + "supplied tool evidence, into \"claims\". "
                        // 输出契约严格拒绝未知字段(温度 0 下同错必复现):键名必须逐字钉死,
                        // 首次真实运行 MiMo 把 claims 写成 claim 即整步失败。
                        + "Respond with a single JSON object whose top-level keys are exactly "
                        + "\"summary\", \"plan\" and \"claims\" (plural, always an array). "
                        // run13 实证:顶层键钉死后,断点前移到 claims 条目内部——schema 里的
                        // 空数组示例等于没有契约,MiMo 把条目键名写成 "claim" 即整步失败。
                        // 条目形状必须逐键写明,并连带钉死引用规则:本步骤 citations 传空集,
                        // validateCitations 在空白名单下拒绝一切 citation,knowledgeBacked=true
                        // 又强制要求 citation——不写明就是下一个必然断点。
                        + "Each entry in \"claims\" must be an object whose keys are exactly "
                        + "\"text\", \"knowledgeBacked\" and \"citationIds\"; any other key name "
                        + "(for example \"claim\") fails validation. No knowledge sources are "
                        + "provided in this step, so \"knowledgeBacked\" must be false and "
                        + "\"citationIds\" must be an empty array in every entry.",
                "",
                "",
                mapper.writeValueAsString(results),
                List.of(),
                "{\"summary\":\"string\",\"plan\":[{\"toolName\":\"string\","
                        + "\"arguments\":{},\"purpose\":\"string\","
                        + "\"expectedEvidence\":\"string\",\"modelRequestId\":\"string\"}],"
                        + "\"claims\":[{\"text\":\"string\","
                        + "\"knowledgeBacked\":false,\"citationIds\":[]}]}",
                "review-plan-v1",
                budget(), budget(), budget(), budget()
        ));
    }

    private AgentModelBudgetPolicy.UsageSnapshot previousUsage(ReviewPlan plan)
            throws JsonProcessingException {
        AgentPlanningCheckpoint checkpoint = mapper.readValue(
                plan.getModelResponseJson(), AgentPlanningCheckpoint.class
        );
        return new AgentModelBudgetPolicy.UsageSnapshot(
                checkpoint.modelCalls(), checkpoint.inputTokens(), checkpoint.outputTokens(),
                checkpoint.latencyMs(), checkpoint.estimatedCost()
        );
    }

    private AgentPromptAssembler.SectionBudget budget() {
        return new AgentPromptAssembler.SectionBudget(8_192, 2_048);
    }

    /**
     * 语义参数完备性预检。基础设施参数已由服务端注入,剩下的空 path/query 只可能是
     * 模型在证据不足时的凭空占位(git.diff 无语义参数,永远可执行)。这类条目直接跳过
     * 而不是让整步以 SECURITY_VIOLATION 失败——真正的策略违规(计划外工具、超预算、
     * 危险字符)仍由循环拒绝并整步失败,防线不变。跳过数记入步骤输出可审计。
     */
    private static boolean hasRequiredSemanticArguments(AgentToolLoop.ToolRequest request) {
        JsonNode arguments = request.arguments() == null
                ? com.fasterxml.jackson.databind.node.NullNode.getInstance()
                : request.arguments();
        if ("git.file".equals(request.toolName())) {
            return !arguments.path("path").asText("").isBlank();
        }
        if ("code.search".equals(request.toolName())) {
            return !arguments.path("query").asText("").isBlank();
        }
        return true;
    }

    /**
     * 基础设施参数服务端权威注入。archiveRef/baseRef/headRef 是本 Run 取证产物的坐标,
     * 由服务端事实(取证检查点)决定,不采信模型输出:模型既无从得知真实引用
     * (规划提示词刻意不含 diff 与归档信息),被注入时也无法把只读工具指向其他 Run
     * 的归档(安全边界)。maxBytes 一并收敛到工具上限,缺省补齐;模型只保留纯语义
     * 参数(path/query 等)的决定权。此前模型参数被原样透传,首次真实运行即因
     * 空 archiveRef 被策略整步拒绝。
     */
    private JsonNode authoritativeArguments(String toolName, JsonNode arguments, AgentAnalysisContext analysis) {
        if (!READ_TOOLS.contains(toolName) || arguments == null || !arguments.isObject()) {
            return arguments;
        }
        ObjectNode normalized = ((ObjectNode) arguments).deepCopy();
        normalized.put("archiveRef", analysis.getArchiveRef());
        if ("git.diff".equals(toolName)) {
            normalized.put("baseRef", analysis.getBaseSha());
            normalized.put("headRef", analysis.getHeadSha());
        }
        int requested = normalized.path("maxBytes").asInt(MAX_TOOL_BYTES);
        normalized.put("maxBytes", Math.max(1, Math.min(requested, MAX_TOOL_BYTES)));
        return normalized;
    }
}
