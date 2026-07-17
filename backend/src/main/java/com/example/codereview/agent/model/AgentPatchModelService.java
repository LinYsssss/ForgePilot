package com.example.codereview.agent.model;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentPatchModelService {

    private final ObjectMapper mapper;
    private final AgentModelCallAuditService audit;
    private final int maxOutputBytes;

    public AgentPatchModelService(
            ObjectMapper mapper,
            AgentModelCallAuditService audit,
            @Value("${app.agent.patch-model.max-output-bytes:262144}") int maxOutputBytes
    ) {
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.audit = audit;
        this.maxOutputBytes = maxOutputBytes;
    }

    public Result generate(
            Long agentRunId,
            AgentModelClient client,
            PromptEnvelope prompt
    ) {
        AgentModelClient.ModelResponse response = client.generate(prompt);
        AgentModelCall call = audit.save(new AgentModelCall(agentRunId, response, prompt, "PATCH"));
        try {
            if (response.content().getBytes(StandardCharsets.UTF_8).length > maxOutputBytes) {
                throw invalid("patch model output exceeds byte limit");
            }
            PatchModelResponse parsed = mapper.readValue(response.content(), PatchModelResponse.class);
            call.complete();
            audit.save(call);
            return new Result(
                    parsed, response.inputTokens(), response.outputTokens(), response.latencyMs()
            );
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            call.fail("invalid patch model JSON");
            audit.save(call);
            throw invalid("patch model output is not schema-valid JSON");
        }
    }

    private AgentStepExecutionException invalid(String message) {
        return new AgentStepExecutionException(AgentFailureType.INVALID_MODEL_OUTPUT, message);
    }

    public record Result(
            PatchModelResponse response,
            long inputTokens,
            long outputTokens,
            long latencyMs
    ) {
        public StructuredAgentModelService.ModelGenerationResult asGeneration() {
            return new StructuredAgentModelService.ModelGenerationResult(
                    new ModelOutputValidator.ValidationResult(true, null, null, null),
                    1, inputTokens, outputTokens, latencyMs
            );
        }
    }
}
