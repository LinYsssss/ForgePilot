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

        AgentStepResult result = new PlanningStepExecutor(
                models, Optional.of(client), prompts, plans, tools, mapper
        ).execute(context(AgentRunStatus.PLANNING));

        assertThat(result.disposition()).isEqualTo(AgentStepResult.Disposition.ADVANCE);
        assertThat(result.nextState()).isEqualTo(AgentRunStatus.EXECUTING_TOOLS);
        ArgumentCaptor<ReviewPlan> saved = ArgumentCaptor.forClass(ReviewPlan.class);
        verify(plans).save(saved.capture());
        assertThat(saved.getValue().getValidatedPlanJson()).contains("call-diff", "call-search");
    }

    @Test
    void executingToolsReusesPersistedModelRequestIds() throws Exception {
        ReviewPlanRepository plans = mock(ReviewPlanRepository.class);
        AgentToolLoop loop = mock(AgentToolLoop.class);
        var response = new StructuredModelResponse("plan", List.of(
                item("git.diff", "call-diff"), item("code.search", "call-search")
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
                item("git.diff", "call-diff"), item("code.search", "call-search")
        ));
        when(models.generateBounded(any(), any(), any(), any(Boolean.class), any()))
                .thenReturn(new StructuredAgentModelService.ModelGenerationResult(
                        new ModelOutputValidator.ValidationResult(true, finalResponse, null, null),
                        1, 80, 25, 15
                ));

        AgentStepResult result = new ExecutingToolsStepExecutor(
                plans, loop, mapper, models, Optional.of(client), prompts,
                AgentModelBudgetPolicy.defaults()
        )
                .execute(context(AgentRunStatus.EXECUTING_TOOLS));

        assertThat(result.nextState()).isEqualTo(AgentRunStatus.RETRIEVING_CONTEXT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentToolLoop.ToolRequest>> requests = ArgumentCaptor.forClass(List.class);
        verify(loop).execute(any(), requests.capture(), any(), any());
        assertThat(requests.getValue()).extracting(AgentToolLoop.ToolRequest::requestId)
                .containsExactly("call-diff", "call-search");
        verify(models).generateBounded(any(), any(), any(), any(Boolean.class), any());
        assertThat(plan.getValidatedPlanJson()).contains("final plan");
    }

    private ReviewPlan.PlanItem item(String tool, String requestId) {
        return new ReviewPlan.PlanItem(
                tool, mapper.createObjectNode(), "inspect", "evidence", requestId
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
