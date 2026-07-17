package com.example.codereview.agent.tool.git;

public sealed interface SandboxToolRequest permits GitDiffRequest, GitFileRequest, CodeSearchRequest, PatchValidateRequest {
    String archiveRef();
}
