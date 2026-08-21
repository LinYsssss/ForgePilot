package com.forgepilot.requirement;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One version of a requirement's prose and the quality result for that version.
 * Frozen once the requirement leaves DRAFT: after that a change publishes
 * {@code seq + 1} instead of editing in place (D011, design.md 6.4).
 */
@Entity
@Table(name = "requirement_revision")
public class RequirementRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "requirement_id", nullable = false)
    private Long requirementId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "background")
    private String background;

    @Column(name = "description")
    private String description;

    /** Points at user_account, not project_member: leaving a project must not erase an accomplished fact. */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** Required from revision 2 onwards; null on the revision created with the requirement. */
    @Column(name = "change_reason")
    private String changeReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quality_json")
    private String qualityJson;

    @Column(name = "quality_version", length = 32)
    private String qualityVersion;

    @Column(name = "quality_checked_at")
    private Instant qualityCheckedAt;

    protected RequirementRevision() {
    }

    public RequirementRevision(Long projectId, Long requirementId, int seq, String title,
            String background, String description, Long createdBy, String changeReason) {
        this.projectId = projectId;
        this.requirementId = requirementId;
        this.seq = seq;
        this.title = title;
        this.background = background;
        this.description = description;
        this.createdBy = createdBy;
        this.changeReason = changeReason;
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

    public int getSeq() {
        return seq;
    }

    public String getTitle() {
        return title;
    }

    public String getBackground() {
        return background;
    }

    public String getDescription() {
        return description;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public String getChangeReason() {
        return changeReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getQualityJson() {
        return qualityJson;
    }

    public String getQualityVersion() {
        return qualityVersion;
    }

    public Instant getQualityCheckedAt() {
        return qualityCheckedAt;
    }

    /**
     * In-place edit, legal only while the requirement is still DRAFT. The quality
     * result described the old prose, so it is cleared in the same transaction
     * (design.md 6.4).
     */
    public void editProse(String title, String background, String description) {
        this.title = title;
        this.background = background;
        this.description = description;
        this.qualityJson = null;
        this.qualityVersion = null;
        this.qualityCheckedAt = null;
    }
}
