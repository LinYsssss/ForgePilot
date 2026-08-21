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
 * The relation between a requirement and a knowledge document, and the single
 * source of truth for attachment ownership (ARCHITECTURE.md 2.3). It lives in
 * {@code requirement} because §1.2 gives this module the attachment relation;
 * {@code knowledge} only ever receives an opaque scope id and never looks a
 * requirement up.
 *
 * <p>Nothing here re-checks ownership. The row's three-column foreign key pins
 * the document's own scope to this requirement, so a public-knowledge document
 * cannot be attached at all, and both id columns are NOT NULL because a NULL
 * would make PostgreSQL skip that check entirely (D015.2).
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
