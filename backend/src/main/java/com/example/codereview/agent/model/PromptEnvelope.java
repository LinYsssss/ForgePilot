package com.example.codereview.agent.model;

import java.util.Objects;

public record PromptEnvelope(
        String trustedPolicy,
        String untrustedRepositoryContent,
        String toolEvidence,
        String outputSchema,
        String promptVersion,
        String schemaVersion
) {
    public PromptEnvelope {
        Objects.requireNonNull(trustedPolicy, "trustedPolicy");
        Objects.requireNonNull(untrustedRepositoryContent, "untrustedRepositoryContent");
        Objects.requireNonNull(toolEvidence, "toolEvidence");
        Objects.requireNonNull(outputSchema, "outputSchema");
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
    }

    public String render() {
        return """
                <trusted_policy version="%s">
                %s
                </trusted_policy>
                <untrusted_repository_content>
                %s
                </untrusted_repository_content>
                <tool_evidence>
                %s
                </tool_evidence>
                <required_output_schema version="%s">
                %s
                </required_output_schema>
                """.formatted(
                safe(promptVersion),
                safe(trustedPolicy),
                safe(untrustedRepositoryContent),
                safe(toolEvidence),
                safe(schemaVersion),
                safe(outputSchema)
        );
    }

    private String safe(String value) {
        return value.replace("</", "<\\/");
    }
}
