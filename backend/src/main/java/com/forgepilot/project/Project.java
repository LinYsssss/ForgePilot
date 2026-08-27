package com.forgepilot.project;

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
 * 一个项目边界。{@code createdBy} 记录创建者且永不变更；
 * LEADER 角色是另一回事，可以转移。
 */
@Entity
@Table(name = "project")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ProjectStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Project() {
    }

    public Project(String name, Long createdBy) {
        this.name = name;
        this.createdBy = createdBy;
        this.status = ProjectStatus.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 归档：项目离开工作区列表，而它的一切都留在原地。
     *
     * <p>之所以是归档而不是硬删，是数据库先做的决定：
     * {@code project_deletion_record.project_id} 有一条指向本表的外键且没有
     * {@code ON DELETE}，于是那张**记录删除行为的台账**本身就会拒绝项目被删除。
     * 要硬删就得先销毁删除审计，而 R5 立那张表正是为了让这件事不可能发生。
     *
     * <p>{@code ARCHIVED} 从 V2 起就在 {@code ck_project_status} 里等着，
     * 一直没有转换实现——这就是那个转换。
     */
    void archive() {
        this.status = ProjectStatus.ARCHIVED;
    }

    /** 取消归档。归档既然不销毁任何东西，它就必须是可逆的，否则「可恢复」只是句空话。 */
    void unarchive() {
        this.status = ProjectStatus.ACTIVE;
    }
}
