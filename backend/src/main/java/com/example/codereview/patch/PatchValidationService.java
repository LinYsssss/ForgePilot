package com.example.codereview.patch;

import com.example.codereview.agent.tool.ToolContext;
import com.example.codereview.agent.tool.ToolResult;
import com.example.codereview.agent.tool.git.PatchValidateRequest;
import com.example.codereview.agent.tool.git.SandboxToolGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PatchValidationService {
    private final PatchCandidateRepository patches;
    private final SandboxToolGateway gateway;
    private final ObjectMapper mapper;

    public PatchValidationService(PatchCandidateRepository patches, SandboxToolGateway gateway, ObjectMapper mapper) {
        this.patches = patches; this.gateway = gateway; this.mapper = mapper;
    }

    @Transactional
    public PatchCandidate validate(Long patchId, String currentHeadSha, String archiveRef,
                                   String validationCommandId, String targetFingerprint,
                                   PatchValidationKind kind, ToolContext context) {
        PatchCandidate candidate = patches.findById(patchId)
                .orElseThrow(() -> new IllegalArgumentException("patch candidate not found"));
        if (!candidate.getAgentRunId().equals(context.agentRunId())) {
            throw new IllegalArgumentException("patch candidate does not belong to agent run");
        }
        if (!candidate.getHeadSha().equals(currentHeadSha)) {
            throw new IllegalArgumentException("stale head SHA");
        }
        ToolResult<Map<String, Object>> tool = gateway.execute(context, new PatchValidateRequest(
                archiveRef, candidate.getHeadSha(), currentHeadSha, validationCommandId, targetFingerprint));
        if (!tool.success() || tool.data() == null) {
            throw new IllegalStateException(tool.error() == null ? "sandbox validation unavailable" : tool.error());
        }
        String output = String.valueOf(tool.data().getOrDefault("output", "{}"));
        try {
            JsonNode result = mapper.readTree(output);
            boolean applied = result.path("applySucceeded").asBoolean(false);
            boolean commandPassed = result.path("buildOrTestPassed").asBoolean(false);
            boolean targetDisappeared = result.path("targetDisappeared").asBoolean(false);
            String log = bounded(result.path("baselineLog").asText() + "\n---\n"
                    + result.path("applyLog").asText() + "\n---\n" + result.path("patchedLog").asText(), 16_384);
            candidate.recordSandboxValidation(kind, new PatchSandboxValidation(
                    result.has("applySucceeded"), applied, commandPassed, targetDisappeared, output, log));
            return patches.save(candidate);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException("sandbox patch result is invalid", ex);
        }
    }

    private static String bounded(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}
