package com.example.codereview.feedback;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "feedback")
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long issueId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String feedbackType;

    @Column(columnDefinition = "text")
    private String comment;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Feedback() {
    }

    public Feedback(Long issueId, Long userId, String feedbackType, String comment) {
        this.issueId = issueId;
        this.userId = userId;
        this.feedbackType = feedbackType;
        this.comment = comment;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(String feedbackType, String comment) {
        this.feedbackType = feedbackType;
        this.comment = comment;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getIssueId() {
        return issueId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getFeedbackType() {
        return feedbackType;
    }

    public String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
