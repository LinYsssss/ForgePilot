package com.forgepilot.requirement;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The stable identity of a requirement. The prose lives in immutable
 * {@link RequirementRevision} rows (D011); this row only carries identity,
 * assignment, status and a pointer to the current revision.
 */
@Entity
@Table(name = "requirement")
public class Requirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** Composite FK to project_member(project_id, user_id): an assignee is always a member of this project. */
    @Column(name = "assignee_id")
    private Long assigneeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RequirementStatus status;

    /** Written through this scalar; the association below is read-only (D013.1 variant A). */
    @Column(name = "current_revision_id")
    private Long currentRevisionId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Read-only navigation for list and detail queries. Every column is
     * insertable=false/updatable=false: project_id and id are already mapped above,
     * and Hibernate refuses a @JoinColumns set that mixes writable and read-only
     * columns (D013.1). The referenced triple is the unique key
     * requirement_revision(project_id, requirement_id, id), so one foreign key
     * proves same project, correct parent and existence at once.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(name = "project_id", referencedColumnName = "project_id",
                insertable = false, updatable = false),
        @JoinColumn(name = "id", referencedColumnName = "requirement_id",
                insertable = false, updatable = false),
        @JoinColumn(name = "current_revision_id", referencedColumnName = "id",
                insertable = false, updatable = false)
    })
    private RequirementRevision currentRevision;

    protected Requirement() {
    }

    public Requirement(Long projectId) {
        this.projectId = projectId;
        this.status = RequirementStatus.DRAFT;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getAssigneeId() {
        return assigneeId;
    }

    public RequirementStatus getStatus() {
        return status;
    }

    public Long getCurrentRevisionId() {
        return currentRevisionId;
    }

    public RequirementRevision getCurrentRevision() {
        return currentRevision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setStatus(RequirementStatus status) {
        this.status = status;
    }

    public void setAssigneeId(Long assigneeId) {
        this.assigneeId = assigneeId;
    }

    /** Third step of the create/publish flow: the composite FK is only checked once this is non-null. */
    public void setCurrentRevisionId(Long currentRevisionId) {
        this.currentRevisionId = currentRevisionId;
    }
}
