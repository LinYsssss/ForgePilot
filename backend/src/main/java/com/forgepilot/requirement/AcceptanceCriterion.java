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
 * An acceptance criterion belonging to one revision. {@code acKey} is the stable
 * cross-revision business identity and is copied unchanged into later revisions;
 * {@code sortOrder} is display only and must never be used as identity (D011).
 */
@Entity
@Table(name = "acceptance_criterion")
public class AcceptanceCriterion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "requirement_revision_id", nullable = false)
    private Long requirementRevisionId;

    @Column(name = "ac_key", nullable = false, length = 64, updatable = false)
    private String acKey;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "text", nullable = false)
    private String text;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AcceptanceCriterion() {
    }

    public AcceptanceCriterion(Long projectId, Long requirementRevisionId, String acKey,
            int sortOrder, String text) {
        this.projectId = projectId;
        this.requirementRevisionId = requirementRevisionId;
        this.acKey = acKey;
        this.sortOrder = sortOrder;
        this.text = text;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getRequirementRevisionId() {
        return requirementRevisionId;
    }

    public String getAcKey() {
        return acKey;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public String getText() {
        return text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** In-place edit while DRAFT. acKey is deliberately absent: it never changes. */
    public void edit(int sortOrder, String text) {
        this.sortOrder = sortOrder;
        this.text = text;
    }
}
