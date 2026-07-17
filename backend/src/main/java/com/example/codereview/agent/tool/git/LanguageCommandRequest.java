package com.example.codereview.agent.tool.git;

import java.util.List;

public record LanguageCommandRequest(
        String archiveRef,
        String commandId,
        List<String> arguments,
        String imageDigest
) implements SandboxToolRequest {
    public LanguageCommandRequest {
        InputValidation.requireArchive(archiveRef);
        if (commandId == null || !commandId.matches("(?:java|python|javascript)\\.[a-z0-9.-]+")) {
            throw new IllegalArgumentException("language command ID is not allowed");
        }
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        if (arguments.stream().anyMatch(value -> value == null || value.indexOf('\0') >= 0)) {
            throw new IllegalArgumentException("language command arguments are invalid");
        }
        if (imageDigest == null
                || !imageDigest.matches("[A-Za-z0-9._/-]+@sha256:[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("language image must be pinned by sha256");
        }
    }
}
