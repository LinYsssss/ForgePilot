package com.example.codereview.language;

import java.util.List;

/** A plugin-declared fixed command ID; it deliberately contains no executable or shell text. */
public record ToolCommand(String commandId, List<String> arguments, String imageDigest, String sourceVersion) {

    public ToolCommand {
        requireText(commandId, "commandId");
        if (!commandId.matches("[a-z][a-z0-9.-]{2,119}")) {
            throw new IllegalArgumentException("commandId must be a fixed registered identifier");
        }
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        if (arguments.stream().anyMatch(value -> value == null || value.indexOf('\0') >= 0)) {
            throw new IllegalArgumentException("arguments must not contain null values or NUL bytes");
        }
        requireText(imageDigest, "imageDigest");
        if (!imageDigest.matches("[A-Za-z0-9._/-]+@sha256:[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("imageDigest must be pinned by sha256");
        }
        requireText(sourceVersion, "sourceVersion");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
