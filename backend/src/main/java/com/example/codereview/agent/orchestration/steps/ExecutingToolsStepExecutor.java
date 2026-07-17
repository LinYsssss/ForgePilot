package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.model.StructuredModelResponse;
import com.example.codereview.agent.model.AgentModelClient;
import com.example.codereview.agent.model.AgentModelBudgetPolicy;
import com.example.codereview.agent.model.AgentPlanningCheckpoint;
import com.example.codereview.agent.model.ModelOutputValidator;
import com.example.codereview.agent.model.StructuredAgentModelService;
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
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final ReviewPlanRepository plans;
    private final AgentToolLoop loop;
    private final ObjectMapper mapper;
    private final StructuredAgentModelService models;
    private final Optional<AgentModelClient> client;
    private final AgentPromptAssembler prompts;
    private final AgentModelBudgetPolicy modelBudget;

    @Autowired
    public ExecutingToolsStepExecutor(
            ReviewPlanRepository plans,
            AgentToolLoop loop,
            ObjectMapper mapper,
            StructuredAgentModelService models,
            Optional<AgentModelClient> client,
            AgentPromptAssembler prompts,
            AgentModelBudgetPolicy modelBudget
    ) {
        this.plans = plans;
        this.loop = loop;
        this.mapper = mapper;
        this.models = models;
        this.client = client;
        this.prompts = prompts;
        this.modelBudget = modelBudget;
    }

    public ExecutingToolsStepExecutor(
            ReviewPlanRepository plans,
            AgentToolLoop loop,
            ObjectMapper mapper
    ) {
        this(plans, loop, mapper, null, Optional.empty(), null, AgentModelBudgetPolicy.defaults());
    }

    public ExecutingToolsStepExecutor() {
        this.plans = null;
        this.loop = null;
        this.mapper = null;
        this.models = null;
        this.client = Optional.empty();
        this.prompts = null;
        this.modelBudget = null;
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
            List<AgentToolLoop.ToolRequest> requests = response.plan().stream()
                    .map(item -> new AgentToolLoop.ToolRequest(
                            item.modelRequestId(), item.toolName(), item.arguments()
                    ))
                    .toList();
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
            StructuredModelResponse finalPlan = response;
            AgentModelBudgetPolicy.UsageSnapshot totalUsage = client.isPresent()
                    ? previousUsage(plan)
                    : new AgentModelBudgetPolicy.UsageSnapshot(0, 0, 0, 0, java.math.BigDecimal.ZERO);
            if (client.isPresent()) {
                var prompt = prompts.assemble(new AgentPromptAssembler.Input(
                        "review-v1",
                        "Use the supplied persisted tool results to return the final schema-valid review plan. "
                                + "Do not request tools outside the original validated plan.",
                        "",
                        "",
                        mapper.writeValueAsString(results),
                        List.of(),
                        "{\"summary\":\"string\",\"plan\":[{\"toolName\":\"string\","
                                + "\"arguments\":{},\"purpose\":\"string\","
                                + "\"expectedEvidence\":\"string\",\"modelRequestId\":\"string\"}],"
                                + "\"claims\":[]}",
                        "review-plan-v1",
                        budget(), budget(), budget(), budget()
                ));
                ReviewPlanValidator.PlanPolicy policy = new ReviewPlanValidator.PlanPolicy(
                        planned, Set.of(), planned, Math.max(1, 8 - requests.size()), true
                );
                StructuredAgentModelService.ModelGenerationResult finalGeneration = models.generateBounded(
                        context.agentRunId(), client.orElseThrow(), prompt, false, policy
                );
                totalUsage = modelBudget.requireWithinBudget(totalUsage, finalGeneration);
                ModelOutputValidator.ValidationResult finalResult = finalGeneration.validation();
                if (!finalResult.valid()) {
                    throw new AgentStepExecutionException(finalResult.failureType(), finalResult.error());
                }
                finalPlan = finalResult.response();
                plan.accept(mapper.writeValueAsString(finalPlan));
                plans.save(plan);
            }
            return new AgentStepResult(
                    "agent-step-result-v1",
                    state(),
                    AgentStepResult.Disposition.ADVANCE,
                    AgentRunStatus.RETRIEVING_CONTEXT,
                    Map.of(
                            "toolRequests", results.size(),
                            "toolSucceeded", succeeded,
                            "finalPlanItems", finalPlan.plan().size(),
                            "modelCalls", totalUsage.modelCalls(),
                            "estimatedCost", totalUsage.estimatedCost().toPlainString()
                    )
            );
        } catch (JsonProcessingException ex) {
            throw new AgentStepExecutionException(
                    AgentFailureType.INVALID_MODEL_OUTPUT, "Validated review plan JSON is invalid", ex
            );
        }
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
}
