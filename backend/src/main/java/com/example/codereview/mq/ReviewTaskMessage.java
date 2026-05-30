package com.example.codereview.mq;

import java.time.Instant;

public record ReviewTaskMessage(
        String messageId,
        Long taskId,
        Long projectId,
        String commitId,
        int retryCount,
        Instant createdAt
) {
    public static ReviewTaskMessage of(Long taskId, Long projectId, String commitId) {
        return new ReviewTaskMessage(
                "review-" + taskId + "-" + commitId,
                taskId,
                projectId,
                commitId,
                0,
                Instant.now()
        );
    }

    public ReviewTaskMessage nextRetry() {
        return new ReviewTaskMessage(
                messageId,
                taskId,
                projectId,
                commitId,
                retryCount + 1,
                Instant.now()
        );
    }
}
