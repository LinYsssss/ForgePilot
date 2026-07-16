package com.example.codereview.agent.tool.git;

public record GitDiffRequest(String archiveRef, String baseRef, String headRef, int maxBytes)
        implements SandboxToolRequest {

    public GitDiffRequest {
        InputValidation.requireArchive(archiveRef);
        InputValidation.requireRef(baseRef, "baseRef");
        InputValidation.requireRef(headRef, "headRef");
        InputValidation.requireMaxBytes(maxBytes);
    }
}
