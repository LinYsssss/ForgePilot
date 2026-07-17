package com.example.codereview.agent.model;

import java.util.concurrent.atomic.AtomicReference;
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
        ModelOutputValidator.ValidationResult result = validator.validate(
                response.content(),
                approved,
                invalid -> {
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
        );

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
        return result;
    }

    private long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}
