package com.example.codereview.ai;

import java.time.Instant;

public final class AiCallLogDtos {

    private AiCallLogDtos() {
    }

    public record AiCallLogResponse(
            Long id,
            Long projectId,
            Long taskId,
            String requestType,
            String provider,
            String model,
            int promptChars,
            int responseChars,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            long latencyMs,
            String status,
            String errorMessage,
            Instant createdAt
    ) {
        public static AiCallLogResponse from(AiCallLog log) {
            return new AiCallLogResponse(
                    log.getId(),
                    log.getProjectId(),
                    log.getTaskId(),
                    log.getRequestType(),
                    log.getProvider(),
                    log.getModel(),
                    log.getPromptChars(),
                    log.getResponseChars(),
                    log.getPromptTokens(),
                    log.getCompletionTokens(),
                    log.getTotalTokens(),
                    log.getLatencyMs(),
                    log.getStatus(),
                    log.getErrorMessage(),
                    log.getCreatedAt()
            );
        }
    }
}
