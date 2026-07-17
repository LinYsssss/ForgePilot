package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.model.AgentModelClient;
import com.example.codereview.agent.model.AgentModelBudgetPolicy;
import com.example.codereview.agent.model.AgentPlanningCheckpoint;
import com.example.codereview.agent.model.ModelOutputValidator;
import com.example.codereview.agent.model.LangChainToolSchemaMapper;
import com.example.codereview.agent.model.StructuredAgentModelService;
import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
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
    private static final String OUTPUT_SCHEMA = """
            {"summary":"string","plan":[{"toolName":"string","arguments":{},
            "purpose":"string","expectedEvidence":"string","modelRequestId":"string"}],"claims":[]}
            """;

    private final StructuredAgentModelService models;
    private final Optional<AgentModelClient> client;
    private final AgentPromptAssembler prompts;
    private final ReviewPlanRepository plans;
    private final AgentToolRegistry tools;
    private final ObjectMapper mapper;
    private final AgentModelBudgetPolicy modelBudget;
    private final LangChainToolSchemaMapper toolSchemas;

    @Autowired
    public PlanningStepExecutor(
            StructuredAgentModelService models,
            Optional<AgentModelClient> client,
            AgentPromptAssembler prompts,
            ReviewPlanRepository plans,
            AgentToolRegistry tools,
            ObjectMapper mapper,
            AgentModelBudgetPolicy modelBudget,
            LangChainToolSchemaMapper toolSchemas
    ) {
        this.models = models;
        this.client = client;
        this.prompts = prompts;
        this.plans = plans;
        this.tools = tools;
        this.mapper = mapper;
        this.modelBudget = modelBudget;
        this.toolSchemas = toolSchemas;
    }

    public PlanningStepExecutor(
            StructuredAgentModelService models,
            Optional<AgentModelClient> client,
            AgentPromptAssembler prompts,
            ReviewPlanRepository plans,
            AgentToolRegistry tools,
            ObjectMapper mapper
    ) {
        this(models, client, prompts, plans, tools, mapper, AgentModelBudgetPolicy.defaults(),
                new LangChainToolSchemaMapper(mapper));
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
        ReviewPlanValidator.PlanPolicy policy = new ReviewPlanValidator.PlanPolicy(
                available, Set.of(), available, 8, true
        );
        var prompt = prompts.assemble(new AgentPromptAssembler.Input(
                "review-v1",
                "Create a bounded review plan using only the supplied registered read-only tools.",
                "",
                "",
                "Available LangChain4j tool specifications: " + toolSchemas.mapAll(descriptors),
                List.of(),
                OUTPUT_SCHEMA,
                "review-plan-v1",
                budget(), budget(), budget(), budget()
        ));
        StructuredAgentModelService.ModelGenerationResult generation = models.generateBounded(
                context.agentRunId(), client.orElseThrow(), prompt, false, policy
        );
        ModelOutputValidator.ValidationResult result = generation.validation();
        AgentModelBudgetPolicy.UsageSnapshot usage = modelBudget.requireWithinBudget(List.of(generation));
        if (!result.valid()) {
            throw new AgentStepExecutionException(result.failureType(), result.error());
        }
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

}
