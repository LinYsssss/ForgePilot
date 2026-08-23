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
 * 需求的稳定身份。文本住在不可变的 {@link RequirementRevision} 行里（D011）；
 * 本行只承载身份、指派、状态，以及一个指向当前修订的指针。
 */
@Entity
@Table(name = "requirement")
public class Requirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 指向 project_member(project_id, user_id) 的复合外键：被指派人必定是本项目成员。 */
    @Column(name = "assignee_id")
    private Long assigneeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RequirementStatus status;

    /** 写入走这个标量字段；下面那个关联是只读的（D013.1 方案 A）。 */
    @Column(name = "current_revision_id")
    private Long currentRevisionId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 供列表与详情查询使用的只读导航。每一列都是 insertable=false/updatable=false：
     * project_id 与 id 在上面已经映射过，而 Hibernate 拒绝一个混合了可写列与
     * 只读列的 @JoinColumns 集合（D013.1）。被引用的三元组是唯一键
     * requirement_revision(project_id, requirement_id, id)，因此这一个外键
     * 同时证明了「同项目」「父级正确」「确实存在」三件事。
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

    /** 创建/发布流程的第三步：只有当这个值非 null 时，复合外键才会被真正检查。 */
    public void setCurrentRevisionId(Long currentRevisionId) {
        this.currentRevisionId = currentRevisionId;
    }
}
