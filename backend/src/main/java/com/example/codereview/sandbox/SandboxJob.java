package com.example.codereview.sandbox;

import java.util.List;

/**
 * A signed unit of work handed to the sandbox runner.
 *
 * <p>Carries everything the runner needs to launch a constrained container and nothing it could use
 * to widen its own trust: a job id, a reference to the workspace archive (never inline secrets or a
 * provider host), the pinned image digest, the whitelisted command id plus its arguments, resource
 * limits, an absolute expiry, and a nonce for replay protection. The job is signed over a canonical
 * serialization by {@link SandboxJobSigner}; the runner verifies signature, expiry, and nonce before
 * acting.
 */
public record SandboxJob(
        String jobId,
        String workspaceArchiveRef,
        String imageDigest,
        String commandId,
        List<String> args,
        Limits limits,
        long expiryEpochSeconds,
        String nonce
) {
    /** Hard resource ceilings enforced on the container. */
    public record Limits(int cpuMillis, int memoryMb, int pids, long timeoutMs) {
        public Limits {
            if (cpuMillis <= 0 || memoryMb <= 0 || pids <= 0 || timeoutMs <= 0) {
                throw new IllegalArgumentException("limits must be positive");
            }
        }
    }

    public SandboxJob {
        requireText(jobId, "jobId");
        requireText(workspaceArchiveRef, "workspaceArchiveRef");
        requireText(imageDigest, "imageDigest");
        requireText(commandId, "commandId");
        requireText(nonce, "nonce");
        if (limits == null) {
            throw new IllegalArgumentException("limits is required");
        }
        args = args == null ? List.of() : List.copyOf(args);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
