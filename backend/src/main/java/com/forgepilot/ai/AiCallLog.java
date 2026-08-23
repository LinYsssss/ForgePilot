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
 * 一次**尝试**，而不是一次调用。重试过的调用会留下两行，这是事后审计
 * “恰好只重试一次”的唯一手段（ARCHITECTURE.md 2.1，用途为“评测与故障定位”）。
 *
 * <p>这不是日志。它是有 schema、无 appender 的项目内数据；Prompt 与响应载荷
 * **有意**不在这里：{@code error} 只放分类信息（例如 {@code HTTP 429}），
 * 绝不放响应体（.trellis/spec/backend/logging-guidelines.md）。
 *
 * <p>表里存在 {@code review_id} 列但故意不做映射：批次 3 要加它的外键，
 * 前提是此前写入的每一行该列都为 NULL（D015.1）。Hibernate 会忽略没有字段
 * 认领的列，因此这道保证不花任何代价。
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

    /** 实际使用的模型，使 Phase 8 的评测得以复现。 */
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

    /** {@code error} 必须始终是分类信息：写入 provider 的响应体等于把回答泄漏进存储。 */
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
