package com.example.codereview.agent.orchestration;

import org.springframework.stereotype.Component;

@Component
public class RepositoryArchiveRefResolver {

    public String resolve(Long agentRunId, String headSha) {
        if (agentRunId == null || agentRunId <= 0) {
            throw new IllegalArgumentException("agentRunId is required");
        }
        if (headSha == null || !headSha.matches("[0-9a-fA-F]{7,80}")) {
            throw new IllegalArgumentException("head SHA is invalid for workspace archive");
        }
        return "workspace://agent-run-" + agentRunId + "-" + headSha.toLowerCase() + ".tar";
    }
}
