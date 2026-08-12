package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.model.AgentModelClient;
import com.example.codereview.agent.model.AgentModelBudgetPolicy;
import com.example.codereview.agent.model.AgentPlanningCheckpoint;
import com.example.codereview.agent.model.ModelOutputValidator;
import com.example.codereview.agent.model.LangChainToolSchemaMapper;
import com.example.codereview.agent.model.PromptEnvelope;
import com.example.codereview.agent.model.StructuredAgentModelService;
import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.orchestration.AgentAnalysisContext;
import com.example.codereview.agent.orchestration.AgentAnalysisContextRepository;
import com.example.codereview.agent.orchestration.AgentChangeAnalysisCheckpoint;
import com.example.codereview.agent.orchestration.AgentStepExecutor;
import com.example.codereview.agent.orchestration.AgentStepResult;
import com.example.codereview.agent.plan.ReviewPlan;
import com.example.codereview.agent.plan.ReviewPlanRepository;
import com.example.codereview.agent.plan.ReviewPlanValidator;
import com.example.codereview.agent.prompt.AgentPromptAssembler;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.tool.AgentToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class PlanningStepExecutor implements AgentStepExecutor {

    private static final Set<String> PLANNING_TOOLS = Set.of("git.diff", "git.file", "code.search");
    // claims 条目形状与执行步终稿 schema 保持同源:空数组示例等于没有契约,
    // 条目键名(text/knowledgeBacked/citationIds)必须逐键写明。规划步不要求产出
    // claims,此处是对同一输出契约的预防性对齐。
    private static final String OUTPUT_SCHEMA = """
            {"summary":"string","plan":[{"toolName":"string","arguments":{},
            "purpose":"string","expectedEvidence":"string","modelRequestId":"string"}],
            "claims":[{"text":"string","knowledgeBacked":false,"citationIds":[]}]}
            """;

    private final StructuredAgentModelService models;
    private final Optional<AgentModelClient> client;
    private final AgentPromptAssembler prompts;
    private final ReviewPlanRepository plans;
    private final AgentToolRegistry tools;
    private final ObjectMapper mapper;
    private final AgentModelBudgetPolicy modelBudget;
    private final LangChainToolSchemaMapper toolSchemas;
    private final AgentAnalysisContextRepository analysisContexts;
    private final ReviewPlanValidator validator;

    @Autowired
    public PlanningStepExecutor(
            StructuredAgentModelService models,
            Optional<AgentModelClient> client,
            AgentPromptAssembler prompts,
            ReviewPlanRepository plans,
            AgentToolRegistry tools,
            ObjectMapper mapper,
            AgentModelBudgetPolicy modelBudget,
            LangChainToolSchemaMapper toolSchemas,
            AgentAnalysisContextRepository analysisContexts,
            ReviewPlanValidator validator
    ) {
        this.models = models;
        this.client = client;
        this.prompts = prompts;
        this.plans = plans;
        this.tools = tools;
        this.mapper = mapper;
        this.modelBudget = modelBudget;
        this.toolSchemas = toolSchemas;
        this.analysisContexts = analysisContexts;
        this.validator = validator;
    }

    public PlanningStepExecutor(
            StructuredAgentModelService models,
            Optional<AgentModelClient> client,
            AgentPromptAssembler prompts,
            ReviewPlanRepository plans,
            AgentToolRegistry tools,
            ObjectMapper mapper,
            ReviewPlanValidator validator
    ) {
        this(models, client, prompts, plans, tools, mapper, AgentModelBudgetPolicy.defaults(),
                new LangChainToolSchemaMapper(mapper), null, validator);
    }

    public PlanningStepExecutor() {
        this.models = null;
        this.client = Optional.empty();
        this.prompts = null;
        this.plans = null;
        this.tools = null;
        this.mapper = null;
        this.modelBudget = null;
        this.toolSchemas = null;
        this.analysisContexts = null;
        this.validator = null;
    }

    @Override
    public AgentRunStatus state() {
        return AgentRunStatus.PLANNING;
    }

    @Override
    public AgentStepResult execute(AgentStepExecutionContext context) {
        if (context.stepType() != state()) {
            throw new IllegalArgumentException("Executor state does not match context");
        }
        if (client.isEmpty()) {
            return AgentStepResult.checkpoint(state());
        }
        if (context.cancellationRequested()) {
            throw new AgentStepExecutionException(AgentFailureType.CANCELED, "Agent run canceled before planning");
        }
        List<AgentToolRegistry.ToolDescriptor> descriptors = tools.descriptors(PLANNING_TOOLS);
        Set<String> available = descriptors.stream()
                .map(AgentToolRegistry.ToolDescriptor::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> activePlugins = activePlugins(context.agentRunId());
        // 提示词声明上限(第一道约束)之后,首次真实运行(run12)MiMo 违规项从 2 降到 1,
        // 但温度 0 下仍会给同一工具排 4 个计划项——提示词工程到头。数值上限是基础设施
        // 约束而非语义判断,按既定哲学转为服务端确定性裁剪兜底(clampOverBudget):
        // 超预算条目被丢弃并以 WARN 留痕,可审计,不再让整个 Run 因可裁剪的越限 FAILED。
        ReviewPlanValidator.PlanPolicy policy = new ReviewPlanValidator.PlanPolicy(
                available, activePlugins, available, 8, true, true
        );
        PromptEnvelope prompt = planningPrompt(context, policy, activePlugins, descriptors);
        StructuredAgentModelService.ModelGenerationResult generation = models.generateBounded(
                context.agentRunId(), client.orElseThrow(), prompt, false, policy
        );
        ModelOutputValidator.ValidationResult result = generation.validation();
        AgentModelBudgetPolicy.UsageSnapshot usage = modelBudget.requireWithinBudget(List.of(generation));
        if (!result.valid()) {
            throw new AgentStepExecutionException(result.failureType(), result.error());
        }
        return persistPlanAndAdvance(context, result, usage, generation, prompt);
    }

    /**
     * 组装规划提示词。变更 diff 喂进规划提示词(assembler 按 diffBudget 截断)。此前该槽传空串,
     * 模型对"改了什么"一无所知,只能凭空编造工具参数(空 path/query),
     * 在执行步被策略拒绝——首次真实运行即暴露。语义参数(看哪个文件、搜什么)
     * 归模型,基础设施参数(archiveRef/base/head)由执行步服务端注入,模型无需知晓。
     */
    private PromptEnvelope planningPrompt(
            AgentStepExecutionContext context,
            ReviewPlanValidator.PlanPolicy policy,
            Set<String> activePlugins,
            List<AgentToolRegistry.ToolDescriptor> descriptors
    ) {
        String changedDiff = analysisContexts == null ? "" : analysisContexts
                .findByAgentRunId(context.agentRunId())
                .map(AgentAnalysisContext::getChangedDiff)
                .orElse("");
        // 验证器强制的数值上限必须白纸黑字写进提示词——首次真实运行(run11)MiMo 不知道
        // per-tool limit=3,给同一工具排了 5 个计划项,ReviewPlanValidator 拒绝
        // (tool repetition exceeds limit 3)→ INVALID_MODEL_OUTPUT,步骤不重试,整个
        // Run 直接 FAILED;温度 0 下同错必复现,只能靠提示词根治。数字引用
        // policy.remainingToolCalls() 与 validator.defaultToolLimit() 同源取值,
        // 不另写字面量,防止提示词与校验规则再次漂移(与执行步终稿提示词同一手法)。
        // r8-R1:指令文本移入 planning-task-v1 模板,数值仍由此处同源注入 %s 槽。
        return prompts.assemble(new AgentPromptAssembler.Input(
                "review-v1",
                prompts.instruction(
                        "planning-task-v1", policy.remainingToolCalls(), validator.defaultToolLimit()
                ),
                changedDiff == null ? "" : changedDiff,
                "",
                "Active language plugins: " + activePlugins
                        + "\nAvailable LangChain4j tool specifications: " + toolSchemas.mapAll(descriptors),
                List.of(),
                OUTPUT_SCHEMA,
                "review-plan-v1",
                budget(), budget(), budget(), budget()
        ));
    }

    /** 已验证计划连同规划检查点(模型用量)落库,并推进到执行工具步。 */
    private AgentStepResult persistPlanAndAdvance(
            AgentStepExecutionContext context,
            ModelOutputValidator.ValidationResult result,
            AgentModelBudgetPolicy.UsageSnapshot usage,
            StructuredAgentModelService.ModelGenerationResult generation,
            PromptEnvelope prompt
    ) {
        try {
            String json = mapper.writeValueAsString(result.response());
            String checkpointJson = mapper.writeValueAsString(new AgentPlanningCheckpoint(
                    "agent-planning-checkpoint-v1",
                    result.response(),
                    usage.modelCalls(),
                    usage.inputTokens(),
                    usage.outputTokens(),
                    usage.latencyMs(),
                    usage.estimatedCost()
            ));
            ReviewPlan plan = plans.findByAgentRunId(context.agentRunId())
                    .orElseGet(() -> new ReviewPlan(context.agentRunId(), "review-plan-v1", checkpointJson));
            plan.accept(json);
            ReviewPlan saved = plans.save(plan);
            return new AgentStepResult(
                    "agent-step-result-v1",
                    state(),
                    AgentStepResult.Disposition.ADVANCE,
                    AgentRunStatus.EXECUTING_TOOLS,
                    Map.of(
                            "planId", saved.getId() == null ? 0L : saved.getId(),
                            "planSchemaVersion", "review-plan-v1",
                            "toolCount", result.response().plan().size(),
                            "promptHash", prompt.promptHash(),
                            "modelCalls", generation.modelCalls(),
                            "inputTokens", generation.inputTokens(),
                            "outputTokens", generation.outputTokens(),
                            "estimatedCost", usage.estimatedCost().toPlainString()
                    )
            );
        } catch (JsonProcessingException ex) {
            throw new AgentStepExecutionException(
                    AgentFailureType.INTERNAL_ERROR, "Validated review plan is not serializable", ex
            );
        }
    }

    private AgentPromptAssembler.SectionBudget budget() {
        return new AgentPromptAssembler.SectionBudget(8_192, 2_048);
    }

    private Set<String> activePlugins(Long agentRunId) {
        if (analysisContexts == null) {
            return Set.of();
        }
        return analysisContexts.findByAgentRunId(agentRunId)
                .map(value -> {
                    try {
                        return Set.copyOf(mapper.readValue(
                                value.getChangeSetJson(), AgentChangeAnalysisCheckpoint.class
                        ).pluginIds());
                    } catch (JsonProcessingException ex) {
                        throw new AgentStepExecutionException(
                                AgentFailureType.INTERNAL_ERROR,
                                "Active language plugin checkpoint is invalid",
                                ex
                        );
                    }
                })
                .orElse(Set.of());
    }

}
