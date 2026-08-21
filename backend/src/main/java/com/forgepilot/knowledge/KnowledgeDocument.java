package com.forgepilot.knowledge;

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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Content shared by project knowledge and requirement attachments. Which one it
 * is depends on {@code sourceRequirementId}: null means public project
 * knowledge, non-null means it belongs to exactly that requirement (D005).
 *
 * <p>The pairing of type and scope is enforced by a database CHECK, and the
 * attachment relation's three-column foreign key pins it further, so this class
 * carries no duplicate of either rule.
 */
@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private KnowledgeSourceType sourceType;

    @Column(name = "source_requirement_id")
    private Long sourceRequirementId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "text")
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KnowledgeStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KnowledgeDocument() {
    }

    private KnowledgeDocument(Long projectId, KnowledgeSourceType sourceType, Long sourceRequirementId,
            String title, String text) {
        this.projectId = projectId;
        this.sourceType = sourceType;
        this.sourceRequirementId = sourceRequirementId;
        this.title = title;
        this.text = text;
        this.status = KnowledgeStatus.PENDING;
    }

    public static KnowledgeDocument attachment(Long projectId, Long requirementId, String title, String text) {
        return new KnowledgeDocument(projectId, KnowledgeSourceType.REQUIREMENT_ATTACHMENT,
                requirementId, title, text);
    }

    public static KnowledgeDocument projectKnowledge(Long projectId, String title, String text) {
        return new KnowledgeDocument(projectId, KnowledgeSourceType.PROJECT_KNOWLEDGE, null, title, text);
    }

    /**
     * Promotion copies rather than rewrites (D005): the original attachment keeps
     * its ownership and history, and the copy starts its own ingestion.
     */
    public KnowledgeDocument copyAsProjectKnowledge() {
        return projectKnowledge(projectId, title, text);
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public KnowledgeSourceType getSourceType() {
        return sourceType;
    }

    public Long getSourceRequirementId() {
        return sourceRequirementId;
    }

    public String getTitle() {
        return title;
    }

    public String getText() {
        return text;
    }

    public KnowledgeStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void markReady() {
        this.status = KnowledgeStatus.READY;
        this.failureReason = null;
    }

    /** The reason is not optional: a database CHECK refuses a FAILED row without one. */
    public void markFailed(String reason) {
        this.status = KnowledgeStatus.FAILED;
        this.failureReason = reason;
    }
}
