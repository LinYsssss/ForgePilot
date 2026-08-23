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
 * 项目知识与需求附件共用的内容载体。到底属于哪一种，取决于
 * {@code sourceRequirementId}：为 null 表示公共项目知识，非 null 表示
 * 它恰好属于那一条需求（D005）。
 *
 * <p>类型与作用域的配对由数据库 CHECK 强制，附件关系的三列外键又进一步
 * 把它钉死，因此本类不重复这两条规则中的任何一条。
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
     * 提升为公共知识采用**复制**而非改写（D005）：原附件保留自己的归属与历史，
     * 副本则开始它自己的入库流程。
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

    /** 原因不是可选项：数据库 CHECK 会拒绝没有原因的 FAILED 行。 */
    public void markFailed(String reason) {
        this.status = KnowledgeStatus.FAILED;
        this.failureReason = reason;
    }
}
