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
            String feedbackType,
            String comment,
            Instant createdAt
    ) {
        public static FeedbackResponse from(Feedback feedback) {
            return new FeedbackResponse(
                    feedback.getId(),
                    feedback.getIssueId(),
                    feedback.getUserId(),
                    feedback.getFeedbackType(),
                    feedback.getComment(),
                    feedback.getCreatedAt()
            );
        }
    }
}
