package com.example.codereview.agent.orchestration;

import java.util.List;

public record AgentRetrievedContextCheckpoint(String version, List<Evidence> evidence) {
    public AgentRetrievedContextCheckpoint {
        if (!"agent-retrieved-context-v1".equals(version)) {
            throw new IllegalArgumentException("unsupported retrieved context version");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public record Evidence(
            String content,
            String citation,
            String sourceName,
            int chunkIndex,
            String documentType,
            String sourceVersion,
            double score,
            boolean untrusted
    ) {
    }
}
