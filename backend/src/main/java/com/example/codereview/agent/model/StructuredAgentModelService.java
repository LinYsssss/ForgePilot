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
        java.util.function.Function<String, String> repair = invalid ->
                attemptRepair(agentRunId, client, prompt, generation, repairCall, repairResponse, invalid);
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

    /**
     * JSON 修复回调:标记原始生成失败并审计,请求 repairJson,把 REPAIR 调用与响应经
     * AtomicReference 侧信道回传(validator 的 {@code Function<String, String>} 回调签名
     * 只允许返回修复后的文本);修复自身失败时同样落一条 FAILED 审计再抛出。
     */
    private String attemptRepair(
            Long agentRunId,
            AgentModelClient client,
            PromptEnvelope prompt,
            AgentModelCall generation,
            AtomicReference<AgentModelCall> repairCall,
            AtomicReference<AgentModelClient.ModelResponse> repairResponse,
            String invalid
    ) {
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
