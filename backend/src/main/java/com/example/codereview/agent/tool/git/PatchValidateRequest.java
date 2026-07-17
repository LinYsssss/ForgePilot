package com.example.codereview.agent.tool.git;

public record PatchValidateRequest(String archiveRef, String boundHeadSha, String currentHeadSha,
                                   String validationCommandId, String targetFingerprint,
                                   String imageDigest) implements SandboxToolRequest {
    public PatchValidateRequest(String archiveRef, String boundHeadSha, String currentHeadSha,
                                String validationCommandId, String targetFingerprint) {
        this(archiveRef, boundHeadSha, currentHeadSha, validationCommandId, targetFingerprint, null);
    }

    public PatchValidateRequest {
        InputValidation.requireArchive(archiveRef);
        InputValidation.requireRef(boundHeadSha, "boundHeadSha");
        InputValidation.requireRef(currentHeadSha, "currentHeadSha");
        requireCommandId(validationCommandId);
        targetFingerprint = targetFingerprint == null ? "" : targetFingerprint;
        if (imageDigest != null
                && !imageDigest.matches("[A-Za-z0-9._/-]+@sha256:[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("patch validation image must be pinned by sha256");
        }
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
