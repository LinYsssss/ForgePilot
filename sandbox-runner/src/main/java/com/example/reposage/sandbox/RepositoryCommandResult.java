package com.example.reposage.sandbox;

public record RepositoryCommandResult(
        SandboxJobStatus status,
        String output,
        boolean truncated,
        String message) {

    public RepositoryCommandResult {
        output = output == null ? "" : output;
    }

    public static RepositoryCommandResult success(String output, boolean truncated) {
        return new RepositoryCommandResult(SandboxJobStatus.SUCCEEDED, output, truncated, null);
    }
}
