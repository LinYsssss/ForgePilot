package com.example.codereview.agent.model;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentFindingModelService {

    private final ObjectMapper mapper;
    private final AgentModelCallAuditService audit;
    private final int maxOutputBytes;

    public AgentFindingModelService(
            ObjectMapper mapper,
            AgentModelCallAuditService audit,
            @Value("${app.agent.model.max-output-bytes:65536}") int maxOutputBytes
    ) {
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.audit = audit;
        this.maxOutputBytes = maxOutputBytes;
    }

    public Result generate(
            Long agentRunId,
            AgentModelClient client,
            PromptEnvelope prompt,
            Set<String> allowedCitations
    ) {
        AgentModelClient.ModelResponse response = client.generate(prompt);
        AgentModelCall call = audit.save(new AgentModelCall(agentRunId, response, prompt, "FINDINGS"));
        try {
            if (response.content().getBytes(StandardCharsets.UTF_8).length > maxOutputBytes) {
                throw invalid("finding model output exceeds byte limit");
            }
            FindingModelResponse parsed = mapper.readValue(response.content(), FindingModelResponse.class);
            validate(parsed, allowedCitations);
            call.complete();
            audit.save(call);
            return new Result(parsed, response.inputTokens(), response.outputTokens(), response.latencyMs());
        } catch (JsonProcessingException ex) {
            call.fail("invalid finding model JSON");
            audit.save(call);
            throw invalid("finding model output is not schema-valid JSON");
        } catch (AgentStepExecutionException ex) {
            call.fail(ex.getMessage());
            audit.save(call);
            throw ex;
        }
    }

    private void validate(FindingModelResponse response, Set<String> allowedCitations) {
        Set<String> allowed = Set.copyOf(allowedCitations);
        for (FindingModelResponse.ModelFinding finding : response.findings()) {
            if (finding.severity() == null || blank(finding.category()) || blank(finding.title())
                    || blank(finding.description())) {
                throw invalid("finding required fields are missing");
            }
            if (finding.citationIds().isEmpty()) {
                throw invalid("model finding requires at least one supplied citation");
            }
            Set<String> unique = new HashSet<>();
            for (String citation : finding.citationIds()) {
                if (!allowed.contains(citation) || !unique.add(citation)) {
                    throw invalid("finding contains unknown or duplicate citation");
                }
            }
        }
    }

    private AgentStepExecutionException invalid(String message) {
        return new AgentStepExecutionException(AgentFailureType.INVALID_MODEL_OUTPUT, message);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record Result(FindingModelResponse response, long inputTokens, long outputTokens, long latencyMs) {
    }
}
