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
 * 需求文本的某一个版本，以及该版本对应的质量检查结果。一旦需求离开 DRAFT
 * 它就被冻结：此后任何变更都是发布 {@code seq + 1}，而不是原地编辑。
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

    /** 指向 user_account 而非 project_member：离开项目不该抹掉一件已经发生的事实。 */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** 从修订 2 起必填；随需求一起创建的那次修订为 null。 */
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
     * 原地编辑，只有在需求仍为 DRAFT 时才合法。质量结果描述的是旧文本，
     * 因此在同一个事务里被清空。
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
