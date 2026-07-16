package com.example.reposage.sandbox;

public record SignedSandboxJob(SandboxJob job, String signature) {

    public SignedSandboxJob {
        if (job == null) {
            throw new IllegalArgumentException("job is required");
        }
        if (signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("signature is required");
        }
    }
}
