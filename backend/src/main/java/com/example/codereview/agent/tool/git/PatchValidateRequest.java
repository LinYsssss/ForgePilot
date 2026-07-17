package com.example.codereview.agent.tool.git;

public record PatchValidateRequest(String archiveRef, String boundHeadSha, String currentHeadSha,
                                   String validationCommandId, String targetFingerprint) implements SandboxToolRequest {
    public PatchValidateRequest {
        InputValidation.requireArchive(archiveRef);
        InputValidation.requireRef(boundHeadSha, "boundHeadSha");
        InputValidation.requireRef(currentHeadSha, "currentHeadSha");
        requireCommandId(validationCommandId);
        targetFingerprint = targetFingerprint == null ? "" : targetFingerprint;
        if (validationCommandId.startsWith("patch.") || validationCommandId.equals("sandbox.health")) {
            throw new IllegalArgumentException("validationCommandId is not allowed");
        }
    }

    private static void requireCommandId(String value) {
        if (value == null || !value.matches("(?:java|python|javascript)\\.[a-z0-9.-]{1,80}")) {
            throw new IllegalArgumentException("validationCommandId is invalid");
        }
    }
}
