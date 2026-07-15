package com.example.reposage.sandbox;

/**
 * Result returned by the runner for a {@link SandboxJob}. Mirror of the backend record. Output is a
 * bounded preview with a {@code truncated} flag — never an unbounded dump of container output.
 */
public record SandboxResult(
        String jobId,
        SandboxJobStatus status,
        Integer exitCode,
        String outputPreview,
        boolean truncated,
        String message
) {
    public SandboxResult {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        outputPreview = outputPreview == null ? "" : outputPreview;
    }
}
