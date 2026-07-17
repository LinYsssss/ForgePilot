package com.example.codereview.agent.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record PromptEnvelope(
        String trustedPolicy,
        String taskInstruction,
        String changedDiff,
        String codeContext,
        String toolEvidence,
        String retrievedKnowledge,
        String outputSchema,
        String promptVersion,
        String promptHash,
        String schemaVersion,
        List<String> truncatedSections,
        List<String> citationIds
) {
    public PromptEnvelope(
            String trustedPolicy,
            String untrustedRepositoryContent,
            String toolEvidence,
            String outputSchema,
            String promptVersion,
            String schemaVersion
    ) {
        this(trustedPolicy, "", untrustedRepositoryContent, "", toolEvidence, "",
                outputSchema, promptVersion, null, schemaVersion, List.of(), List.of());
    }

    public PromptEnvelope {
        trustedPolicy = Objects.requireNonNull(trustedPolicy, "trustedPolicy");
        taskInstruction = Objects.requireNonNull(taskInstruction, "taskInstruction");
        changedDiff = Objects.requireNonNull(changedDiff, "changedDiff");
        codeContext = Objects.requireNonNull(codeContext, "codeContext");
        toolEvidence = Objects.requireNonNull(toolEvidence, "toolEvidence");
        retrievedKnowledge = Objects.requireNonNull(retrievedKnowledge, "retrievedKnowledge");
        outputSchema = Objects.requireNonNull(outputSchema, "outputSchema");
        promptVersion = Objects.requireNonNull(promptVersion, "promptVersion");
        schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
        truncatedSections = truncatedSections == null ? List.of() : List.copyOf(truncatedSections);
        citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        String calculated = calculateHash(
                trustedPolicy, taskInstruction, changedDiff, codeContext, toolEvidence,
                retrievedKnowledge, outputSchema, promptVersion, schemaVersion,
                truncatedSections, citationIds
        );
        if (promptHash == null || promptHash.isBlank()) {
            promptHash = calculated;
        } else if (!promptHash.equals(calculated)) {
            throw new IllegalArgumentException("prompt hash does not match prompt content");
        }
    }

    public String render() {
        return systemMessage() + "\n" + userMessage();
    }

    public String systemMessage() {
        return """
                <trusted_policy version="%s">
                %s
                </trusted_policy>
                <trusted_task_instruction>
                %s
                </trusted_task_instruction>
                <required_output_schema version="%s">
                %s
                </required_output_schema>
                """.formatted(
                safe(promptVersion),
                safe(trustedPolicy),
                safe(taskInstruction),
                safe(schemaVersion),
                safe(outputSchema)
        );
    }

    public String userMessage() {
        return """
                <untrusted_changed_diff>
                %s
                </untrusted_changed_diff>
                <untrusted_code_context>
                %s
                </untrusted_code_context>
                <untrusted_tool_evidence>
                %s
                </untrusted_tool_evidence>
                <untrusted_retrieved_knowledge>
                %s
                </untrusted_retrieved_knowledge>
                """.formatted(
                safe(changedDiff),
                safe(codeContext),
                safe(toolEvidence),
                safe(retrievedKnowledge)
        );
    }

    public String untrustedRepositoryContent() {
        return changedDiff + (codeContext.isBlank() ? "" : "\n" + codeContext);
    }

    private static String safe(String value) {
        return value.replace("</", "<\\/");
    }

    private static String calculateHash(Object... values) {
        try {
            StringBuilder canonical = new StringBuilder();
            for (Object value : values) {
                canonical.append(value).append('\u001f');
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash prompt", ex);
        }
    }
}
