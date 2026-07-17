package com.example.codereview.agent.model;

import java.util.concurrent.atomic.AtomicReference;
import com.example.codereview.agent.plan.ReviewPlanValidator;
import org.springframework.stereotype.Service;

@Service
public class StructuredAgentModelService {

    private final ModelOutputValidator validator;
    private final AgentModelCallAuditService audit;

    public StructuredAgentModelService(
            ModelOutputValidator validator,
            AgentModelCallAuditService audit
    ) {
        this.validator = validator;
        this.audit = audit;
    }

    public ModelOutputValidator.ValidationResult generate(
            Long agentRunId,
            AgentModelClient client,
            PromptEnvelope prompt,
            boolean approved
    ) {
        return generateInternal(agentRunId, client, prompt, approved, null).validation();
    }

    public ModelOutputValidator.ValidationResult generate(
            Long agentRunId,
            AgentModelClient client,
            PromptEnvelope prompt,
            boolean approved,
            ReviewPlanValidator.PlanPolicy planPolicy
    ) {
        return generateInternal(agentRunId, client, prompt, approved, planPolicy).validation();
    }

    public ModelGenerationResult generateBounded(
            Long agentRunId,
            AgentModelClient client,
            PromptEnvelope prompt,
            boolean approved,
            ReviewPlanValidator.PlanPolicy planPolicy
    ) {
        return generateInternal(agentRunId, client, prompt, approved, planPolicy);
    }

    private ModelGenerationResult generateInternal(
            Long agentRunId,
            AgentModelClient client,
            PromptEnvelope prompt,
            boolean approved,
            ReviewPlanValidator.PlanPolicy planPolicy
    ) {
        AgentModelClient.ModelResponse response;
        long generateStarted = System.nanoTime();
        try {
            response = client.generate(prompt);
        } catch (RuntimeException failure) {
            audit.save(AgentModelCall.failedAttempt(
                    agentRunId,
                    client.provider(),
                    client.model(),
                    prompt,
                    "GENERATE",
                    elapsedMs(generateStarted),
                    failure
            ));
            throw failure;
        }

        AgentModelCall generation = audit.save(
                new AgentModelCall(agentRunId, response, prompt, "GENERATE")
        );
        AtomicReference<AgentModelCall> repairCall = new AtomicReference<>();
        AtomicReference<AgentModelClient.ModelResponse> repairResponse = new AtomicReference<>();
        java.util.function.Function<String, String> repair = invalid -> {
                    generation.fail("model output required JSON repair");
                    audit.save(generation);
                    long repairStarted = System.nanoTime();
                    try {
                        AgentModelClient.ModelResponse repaired = client.repairJson(
                                invalid,
                                "Output must match schema " + prompt.schemaVersion()
                        );
                        AgentModelCall persisted = audit.save(
                                new AgentModelCall(agentRunId, repaired, prompt, "REPAIR")
                        );
                        repairCall.set(persisted);
                        repairResponse.set(repaired);
                        return repaired.content();
                    } catch (RuntimeException failure) {
                        audit.save(AgentModelCall.failedAttempt(
                                agentRunId,
                                client.provider(),
                                client.model(),
                                prompt,
                                "REPAIR",
                                elapsedMs(repairStarted),
                                failure
                        ));
                        throw failure;
                    }
                };
        ModelOutputValidator.ValidationResult result = planPolicy == null
                ? validator.validate(response.content(), approved, prompt.citationIds(), repair)
                : validator.validate(response.content(), approved, prompt.citationIds(), planPolicy, repair);

        AgentModelCall terminalCall = repairCall.get();
        if (terminalCall == null) {
            terminalCall = generation;
        }
        if (result.valid()) {
            terminalCall.complete();
        } else {
            terminalCall.fail(result.error());
        }
        audit.save(terminalCall);
        AgentModelClient.ModelResponse repaired = repairResponse.get();
        return new ModelGenerationResult(
                result,
                repaired == null ? 1 : 2,
                response.inputTokens() + (repaired == null ? 0 : repaired.inputTokens()),
                response.outputTokens() + (repaired == null ? 0 : repaired.outputTokens()),
                response.latencyMs() + (repaired == null ? 0 : repaired.latencyMs())
        );
    }

    private long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    public record ModelGenerationResult(
            ModelOutputValidator.ValidationResult validation,
            int modelCalls,
            long inputTokens,
            long outputTokens,
            long latencyMs
    ) {
    }
}
