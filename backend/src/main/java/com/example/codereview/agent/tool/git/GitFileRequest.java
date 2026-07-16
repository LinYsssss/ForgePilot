package com.example.codereview.agent.tool.git;

public record GitFileRequest(String archiveRef, String path, int maxBytes)
        implements SandboxToolRequest {

    public GitFileRequest {
        InputValidation.requireArchive(archiveRef);
        InputValidation.requireRelativePath(path);
        InputValidation.requireMaxBytes(maxBytes);
    }
}
