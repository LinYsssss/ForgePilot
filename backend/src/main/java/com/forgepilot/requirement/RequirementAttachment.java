package com.forgepilot.requirement;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 需求与知识文档之间的关系，也是附件归属的唯一事实源（ARCHITECTURE.md 2.3）。
 * 它住在 {@code requirement} 里，因为 §1.2 把附件关系判给了本模块；
 * {@code knowledge} 永远只收到一个不透明的作用域 id，从不去查询需求。
 *
 * <p>这里不重复校验归属。本行的三列外键把文档自身的作用域钉死在这条需求上，
 * 因此一份公共知识文档根本无法被挂为附件；两个 id 列都是 NOT NULL，
 * 因为只要有一个为 NULL，PostgreSQL 就会整体跳过那次检查（D015.2）。
 */
@Entity
@Table(name = "requirement_attachment")
public class RequirementAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "requirement_id", nullable = false)
    private Long requirementId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RequirementAttachment() {
    }

    public RequirementAttachment(Long projectId, Long requirementId, Long documentId) {
        this.projectId = projectId;
        this.requirementId = requirementId;
        this.documentId = documentId;
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

    public Long getDocumentId() {
        return documentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
