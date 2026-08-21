package com.forgepilot.ai;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One attempt against a provider — not one call. A retried call leaves two rows,
 * which is the only way "exactly one retry" can be audited after the fact
 * (ARCHITECTURE.md 2.1, purpose "评测与故障定位").
 *
 * <p>This is not logging. It is project-scoped data with a schema and no
 * appender, and its prompt and response payloads are deliberately not here:
 * {@code error} carries a classification such as {@code HTTP 429}, never a body
 * (.trellis/spec/backend/logging-guidelines.md).
 *
 * <p>{@code review_id} exists in the table but is intentionally unmapped: batch
 * 3 may only add its foreign key if every row written before then is NULL
 * (D015.1). Hibernate ignores columns no field claims, so the guarantee costs
 * nothing.
 */
@Entity
@Table(name = "ai_call_log")
public class AiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "requirement_id")
    private Long requirementId;

    @Column(name = "requirement_revision_id")
    private Long requirementRevisionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "use_case", nullable = false, length = 32)
    private AiUseCase useCase;

    /** The model actually used, so a Phase 8 evaluation can be reproduced. */
    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @Column(name = "prompt_token")
    private Integer promptToken;

    @Column(name = "completion_token")
    private Integer completionToken;

    @Column(name = "total_token")
    private Integer totalToken;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AiCallStatus status;

    @Column(name = "error")
    private String error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiCallLog() {
    }

    private AiCallLog(AiCallContext context, AiUseCase useCase, String model, int latencyMs,
            AiCallStatus status) {
        this.projectId = context.projectId();
        this.requirementId = context.requirementId();
        this.requirementRevisionId = context.requirementRevisionId();
        this.useCase = useCase;
        this.model = model;
        this.latencyMs = latencyMs;
        this.status = status;
    }

    static AiCallLog success(AiCallContext context, AiUseCase useCase, String model, int latencyMs,
            Integer promptToken, Integer completionToken, Integer totalToken) {
        AiCallLog attempt = new AiCallLog(context, useCase, model, latencyMs, AiCallStatus.SUCCESS);
        attempt.promptToken = promptToken;
        attempt.completionToken = completionToken;
        attempt.totalToken = totalToken;
        return attempt;
    }

    /** {@code error} must stay a classification: a provider body would leak the answer into storage. */
    static AiCallLog failure(AiCallContext context, AiUseCase useCase, String model, int latencyMs,
            AiCallStatus status, String error) {
        AiCallLog attempt = new AiCallLog(context, useCase, model, latencyMs, status);
        attempt.error = error;
        return attempt;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getRequirementId() {
        return requirementId;
    }

    public Long getRequirementRevisionId() {
        return requirementRevisionId;
    }

    public AiUseCase getUseCase() {
        return useCase;
    }

    public String getModel() {
        return model;
    }

    public Integer getPromptToken() {
        return promptToken;
    }

    public Integer getCompletionToken() {
        return completionToken;
    }

    public Integer getTotalToken() {
        return totalToken;
    }

    public int getLatencyMs() {
        return latencyMs;
    }

    public AiCallStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
