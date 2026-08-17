package com.example.codereview.requirement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "requirement",
        uniqueConstraints = @UniqueConstraint(name = "uq_requirement_project_seq", columnNames = {"projectId", "seq"}))
public class RequirementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    /** 项目内人读编号(REQ-<seq>),取号见 RequirementService。 */
    @Column(nullable = false)
    private Long seq;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String background;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 16)
    private String priority;

    private Long assigneeId;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private Long createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RequirementEntity() {
    }

    public RequirementEntity(Long projectId, Long seq, String title, String background,
                             String description, String priority, Long createdBy) {
        this.projectId = projectId;
        this.seq = seq;
        this.title = title;
        this.background = background;
        this.description = description;
        this.priority = priority;
        this.status = RequirementStatus.DRAFT.name();
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getSeq() {
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

    public String getPriority() {
        return priority;
    }

    public Long getAssigneeId() {
        return assigneeId;
    }

    public RequirementStatus getStatus() {
        return RequirementStatus.fromName(status);
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void updateContent(String title, String background, String description, String priority) {
        this.title = title;
        this.background = background;
        this.description = description;
        this.priority = priority;
        this.updatedAt = Instant.now();
    }

    public void assign(Long assigneeId) {
        this.assigneeId = assigneeId;
        this.updatedAt = Instant.now();
    }

    public void moveTo(RequirementStatus next) {
        this.status = next.name();
        this.updatedAt = Instant.now();
    }
}
