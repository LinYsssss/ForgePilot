package com.example.codereview.feedback;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class FeedbackDtos {

    private FeedbackDtos() {
    }

    public record FeedbackRequest(
            @NotBlank String feedbackType,
            @Size(max = 1000) String comment
    ) {
    }

    public record FeedbackResponse(
            Long feedbackId,
            Long issueId,
            Long userId,
            String username,
            String feedbackType,
            String comment,
            boolean mine,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static FeedbackResponse from(Feedback feedback, String username, Long currentUserId) {
            return new FeedbackResponse(
                    feedback.getId(),
                    feedback.getIssueId(),
                    feedback.getUserId(),
                    username,
                    feedback.getFeedbackType(),
                    feedback.getComment(),
                    feedback.getUserId().equals(currentUserId),
                    feedback.getCreatedAt(),
                    feedback.getUpdatedAt()
            );
        }
    }
}
