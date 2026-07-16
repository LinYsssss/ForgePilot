package com.example.codereview.agent.tool.git;

public record CodeSearchRequest(String archiveRef, String query, int maxResults, int maxBytes)
        implements SandboxToolRequest {

    public CodeSearchRequest {
        InputValidation.requireArchive(archiveRef);
        if (query == null || query.isBlank() || query.length() > 512) {
            throw new IllegalArgumentException("query must be 1-512 characters");
        }
        if (maxResults <= 0 || maxResults > 1_000) {
            throw new IllegalArgumentException("maxResults must be 1-1000");
        }
        InputValidation.requireMaxBytes(maxBytes);
    }
}
