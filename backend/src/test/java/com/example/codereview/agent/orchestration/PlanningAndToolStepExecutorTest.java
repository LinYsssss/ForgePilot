package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.model.AgentModelClient;
import com.example.codereview.agent.model.AgentModelBudgetPolicy;
import com.example.codereview.agent.model.AgentPlanningCheckpoint;
import com.example.codereview.agent.model.ModelOutputValidator;
import com.example.codereview.agent.model.PromptEnvelope;
import com.example.codereview.agent.model.StructuredAgentModelService;
import com.example.codereview.agent.model.StructuredModelResponse;
import com.example.codereview.agent.orchestration.steps.ExecutingToolsStepExecutor;
import com.example.codereview.agent.orchestration.steps.PlanningStepExecutor;
import com.example.codereview.agent.plan.ReviewPlan;
import com.example.codereview.agent.plan.ReviewPlanRepository;
import com.example.codereview.agent.plan.ReviewPlanValidator;
import com.example.codereview.agent.prompt.AgentPromptAssembler;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.tool.AgentToolRegistry;
import com.example.codereview.agent.tool.ToolRiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PlanningAndToolStepExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void planningUsesStructuredModelAndPersistsRequestIds() {
        StructuredAgentModelService models = mock(StructuredAgentModelService.class);
        AgentModelClient client = mock(AgentModelClient.class);
        AgentPromptAssembler prompts = mock(AgentPromptAssembler.class);
        ReviewPlanRepository plans = mock(ReviewPlanRepository.class);
        AgentToolRegistry tools = mock(AgentToolRegistry.class);
        PromptEnvelope prompt = new PromptEnvelope("policy", "task", "", "", "", "", "{}",
                "review-v1", null, "review-plan-v1", List.of(), List.of());
        when(prompts.assemble(any())).thenReturn(prompt);
        when(tools.descriptors(any())).thenReturn(List.of(
                new AgentToolRegistry.ToolDescriptor(
                        "git.diff", "Read diff", TestInput.class, ToolRiskLevel.READ_ONLY
                ),
                new AgentToolRegistry.ToolDescriptor(
                        "code.search", "Search code", TestInput.class, ToolRiskLevel.READ_ONLY
                )
        ));
        var response = new StructuredModelResponse("plan", List.of(
                item("git.diff", "call-diff"), item("code.search", "call-search")
        ));
        when(models.generateBounded(any(), any(), any(), any(Boolean.class), any()))
                .thenReturn(new StructuredAgentModelService.ModelGenerationResult(
                        new ModelOutputValidator.ValidationResult(true, response, null, null),
                        1, 120, 40, 25
                ));
        when(plans.findByAgentRunId(1L)).thenReturn(Optional.empty());
        when(plans.save(any())).thenAnswer(call -> call.getArgument(0));
        // 与 config/app-agent.yml 的 app.agent.plan.default-tool-limit:3 同值;断言只经由
        // validator.defaultToolLimit() 取数,不在断言里另写魔数。
        ReviewPlanValidator validator = new ReviewPlanValidator(tools, mapper, 3, 16_384);

        AgentStepResult result = new PlanningStepExecutor(
                models, Optional.of(client), prompts, plans, tools, mapper, validator
        ).execute(context(AgentRunStatus.PLANNING));

        assertThat(result.disposition()).isEqualTo(AgentStepResult.Disposition.ADVANCE);
        assertThat(result.nextState()).isEqualTo(AgentRunStatus.EXECUTING_TOOLS);
        ArgumentCaptor<ReviewPlan> saved = ArgumentCaptor.forClass(ReviewPlan.class);
        verify(plans).save(saved.capture());
        assertThat(saved.getValue().getValidatedPlanJson()).contains("call-diff", "call-search");
        // run11 实证:验证器强制的数值上限(单工具重复、计划项总数)不写进提示词,
        // 模型就会排出 5 个同工具项 → tool repetition exceeds limit 3 → 整步失败且不重试。
        // 两个数字分别从传给模型服务的 policy 与 validator 同源取值,锁死"提示词与
        // 校验器不得漂移"的契约。
        ArgumentCaptor<AgentPromptAssembler.Input> promptInput =
                ArgumentCaptor.forClass(AgentPromptAssembler.Input.class);
        verify(prompts).assemble(promptInput.capture());
        ArgumentCaptor<ReviewPlanValidator.PlanPolicy> policy =
                ArgumentCaptor.forClass(ReviewPlanValidator.PlanPolicy.class);
        verify(models).generateBounded(any(), any(), any(), any(Boolean.class), policy.capture());
        assertThat(promptInput.getValue().taskInstruction())
                .contains("at most " + policy.getValue().remainingToolCalls() + " items in total")
                .contains("at most " + validator.defaultToolLimit() + " times");
        // claims 条目形状与执行步终稿 schema 同源对齐:空数组示例等于没有契约。
        assertThat(promptInput.getValue().outputSchema())
                .contains("\"text\"", "\"knowledgeBacked\"", "\"citationIds\"");
        // run12 实证:提示词声明上限后违规项 2→1 但仍越限,规划策略必须开启服务端裁剪兜底。
        assertThat(policy.getValue().clampOverBudget()).isTrue();
    }

    @Test
    void executingToolsReusesPersistedModelRequestIds() throws Exception {
        ReviewPlanRepository plans = mock(ReviewPlanRepository.class);
        AgentToolLoop loop = mock(AgentToolLoop.class);
        var response = new StructuredModelResponse("plan", List.of(
                item("git.diff", "call-diff"), searchItem("call-search")
        ));
        String json = mapper.writeValueAsString(response);
        String checkpoint = mapper.writeValueAsString(new AgentPlanningCheckpoint(
                "agent-planning-checkpoint-v1", response, 1, 100, 30, 20,
                new java.math.BigDecimal("0.00019")
        ));
        ReviewPlan plan = new ReviewPlan(1L, "review-plan-v1", checkpoint);
        plan.accept(json);
        when(plans.findByAgentRunId(1L)).thenReturn(Optional.of(plan));
        when(loop.execute(any(), any(), any(), any())).thenReturn(List.of(
                new AgentToolLoop.ToolResultEnvelope(
                        "call-diff", "git.diff", AgentToolLoop.ToolStatus.SUCCESS,
                        mapper.nullNode(), null
                ),
                new AgentToolLoop.ToolResultEnvelope(
                        "call-search", "code.search", AgentToolLoop.ToolStatus.SUCCESS,
                        mapper.nullNode(), null
                )
        ));

        StructuredAgentModelService models = mock(StructuredAgentModelService.class);
        AgentModelClient client = mock(AgentModelClient.class);
        AgentPromptAssembler prompts = mock(AgentPromptAssembler.class);
        when(prompts.assemble(any())).thenReturn(new PromptEnvelope(
                "policy", "final", "", "", "results", "", "{}",
                "review-v1", null, "review-plan-v1", List.of(), List.of()
        ));
        var finalResponse = new StructuredModelResponse("final plan", List.of(
                item("git.diff", "call-diff"), searchItem("call-search")
        ));
        when(models.generateBounded(any(), any(), any(), any(Boolean.class), any()))
                .thenReturn(new StructuredAgentModelService.ModelGenerationResult(
                        new ModelOutputValidator.ValidationResult(true, finalResponse, null, null),
                        1, 80, 25, 15
                ));

        AgentStepResult result = new ExecutingToolsStepExecutor(
                plans, loop, mapper, models, Optional.of(client), prompts,
                AgentModelBudgetPolicy.defaults(), analysisContexts()
        )
                .execute(context(AgentRunStatus.EXECUTING_TOOLS));

        assertThat(result.nextState()).isEqualTo(AgentRunStatus.RETRIEVING_CONTEXT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentToolLoop.ToolRequest>> requests = ArgumentCaptor.forClass(List.class);
        verify(loop).execute(any(), requests.capture(), any(), any());
        assertThat(requests.getValue()).extracting(AgentToolLoop.ToolRequest::requestId)
                .containsExactly("call-diff", "call-search");
        // 基础设施参数不采信模型输出:archiveRef 由服务端从取证检查点权威注入。
        assertThat(requests.getValue()).allSatisfy(request ->
                assertThat(request.arguments().path("archiveRef").asText())
                        .isEqualTo("agent-run-1-abcdef1.tar"));
        verify(models).generateBounded(any(), any(), any(), any(Boolean.class), any());
        assertThat(plan.getValidatedPlanJson()).contains("final plan");
        // run13 实证:顶层键钉死后断点前移到 claims 条目内部——schema 里空数组示例
        // 等于没有契约,MiMo 把条目键名写成 "claim" 即整步失败。钉死条目三键名与
        // "本步无知识源 → knowledgeBacked=false、citationIds=[]" 的引用规则。
        ArgumentCaptor<AgentPromptAssembler.Input> finalPrompt =
                ArgumentCaptor.forClass(AgentPromptAssembler.Input.class);
        verify(prompts).assemble(finalPrompt.capture());
        assertThat(finalPrompt.getValue().outputSchema())
                .contains("\"text\"", "\"knowledgeBacked\"", "\"citationIds\"")
                .contains("\"knowledgeBacked\":false");
        assertThat(finalPrompt.getValue().taskInstruction())
                .contains("\"text\", \"knowledgeBacked\" and \"citationIds\"")
                .contains("\"knowledgeBacked\" must be false")
                .contains("\"citationIds\" must be an empty array");
    }

    // 回执预算语义:终稿提示词要求"逐字回写原计划全部条目",预算必须等于原计划条数。
    // 用 ≥5 项场景钉死——若回退到 8-requests 的写法,5 项计划的合规回执必超预算。
    @Test
    void finalResponsePolicyBudgetMatchesOriginalPlanSize() throws Exception {
        ReviewPlanRepository plans = mock(ReviewPlanRepository.class);
        AgentToolLoop loop = mock(AgentToolLoop.class);
        var response = new StructuredModelResponse("plan", List.of(
                item("git.diff", "call-1"), item("git.diff", "call-2"), item("git.diff", "call-3"),
                searchItem("call-4"), searchItem("call-5")
        ));
        String checkpoint = mapper.writeValueAsString(new AgentPlanningCheckpoint(
                "agent-planning-checkpoint-v1", response, 1, 100, 30, 20,
                new java.math.BigDecimal("0.00019")
        ));
        ReviewPlan plan = new ReviewPlan(1L, "review-plan-v1", checkpoint);
        plan.accept(mapper.writeValueAsString(response));
        when(plans.findByAgentRunId(1L)).thenReturn(Optional.of(plan));
        when(loop.execute(any(), any(), any(), any())).thenReturn(List.of(
                new AgentToolLoop.ToolResultEnvelope(
                        "call-1", "git.diff", AgentToolLoop.ToolStatus.SUCCESS, mapper.nullNode(), null
                )
        ));

        StructuredAgentModelService models = mock(StructuredAgentModelService.class);
        AgentModelClient client = mock(AgentModelClient.class);
        AgentPromptAssembler prompts = mock(AgentPromptAssembler.class);
        when(prompts.assemble(any())).thenReturn(new PromptEnvelope(
                "policy", "final", "", "", "results", "", "{}",
                "review-v1", null, "review-plan-v1", List.of(), List.of()
        ));
        when(models.generateBounded(any(), any(), any(), any(Boolean.class), any()))
                .thenReturn(new StructuredAgentModelService.ModelGenerationResult(
                        new ModelOutputValidator.ValidationResult(
                                true, new StructuredModelResponse("final plan", response.plan()), null, null
                        ),
                        1, 80, 25, 15
                ));

        new ExecutingToolsStepExecutor(
                plans, loop, mapper, models, Optional.of(client), prompts,
                AgentModelBudgetPolicy.defaults(), analysisContexts()
        ).execute(context(AgentRunStatus.EXECUTING_TOOLS));

        ArgumentCaptor<ReviewPlanValidator.PlanPolicy> policy =
                ArgumentCaptor.forClass(ReviewPlanValidator.PlanPolicy.class);
        verify(models).generateBounded(any(), any(), any(), any(Boolean.class), policy.capture());
        assertThat(policy.getValue().remainingToolCalls()).isEqualTo(response.plan().size());
        // 回执是已执行证据的收据,裁剪等于撕毁收据——回执校验必须保持硬失败语义。
        assertThat(policy.getValue().clampOverBudget()).isFalse();
    }

    private AgentAnalysisContextRepository analysisContexts() {
        AgentAnalysisContextRepository analysisContexts = mock(AgentAnalysisContextRepository.class);
        AgentAnalysisContext analysis = mock(AgentAnalysisContext.class);
        when(analysis.getArchiveRef()).thenReturn("agent-run-1-abcdef1.tar");
        when(analysis.getBaseSha()).thenReturn("abcdef0");
        when(analysis.getHeadSha()).thenReturn("head");
        when(analysisContexts.findByAgentRunId(1L)).thenReturn(Optional.of(analysis));
        return analysisContexts;
    }

    private ReviewPlan.PlanItem item(String tool, String requestId) {
        return new ReviewPlan.PlanItem(
                tool, mapper.createObjectNode(), "inspect", "evidence", requestId
        );
    }

    /** 语义参数完备的 code.search 计划项:空 query 的盲目占位项会被执行步预检跳过。 */
    private ReviewPlan.PlanItem searchItem(String requestId) {
        return new ReviewPlan.PlanItem(
                "code.search", mapper.createObjectNode().put("query", "promotion"),
                "inspect", "evidence", requestId
        );
    }

    private AgentStepExecutionContext context(AgentRunStatus status) {
        return new AgentStepExecutionContext(
                1L, 11L, 2L, 3L, 4L, "head", status, 3, 0, "trace", false
        );
    }

    record TestInput(String value) {
    }
}
