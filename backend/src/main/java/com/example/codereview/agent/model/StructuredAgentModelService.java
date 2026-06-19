package com.example.codereview.agent.model;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StructuredAgentModelService {

    private final ModelOutputValidator validator;
    private final AgentModelCallRepository calls;

    public StructuredAgentModelService(
            ModelOutputValidator validator,
            AgentModelCallRepository calls
    ) {
        this.validator = validator;
        this.calls = calls;
    }

    @Transactional
    public ModelOutputValidator.ValidationResult generate(
            Long agentRunId,
            AgentModelClient client,
            PromptEnvelope prompt,
            boolean approved
    ) {
        AgentModelClient.ModelResponse response = client.generate(prompt);
        AgentModelCall call = calls.save(new AgentModelCall(agentRunId, response, prompt));
        ModelOutputValidator.ValidationResult result = validator.validate(
                response.content(),
                approved,
                invalid -> client.repairJson(invalid, "Output must match schema " + prompt.schemaVersion())
        );
        if (result.valid()) {
            call.complete();
        } else {
            call.fail(result.error());
        }
        calls.save(call);
        return result;
    }
}
