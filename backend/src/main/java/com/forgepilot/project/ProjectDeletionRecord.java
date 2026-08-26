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

/**
 * 一次删除这件事实本身，永久保存。
 *
 * <p>{@code actorUserId} 指向 {@code user_account} 而非 {@code project_member}，
 * 与 {@code FindingEvent.actorId} 同理：删除的人此后离开项目，不该抹掉这条记录。
 *
 * <p>{@link #resourceId} 刻意没有外键。两类资源是硬删，行已经不存在，根本没有
 * 可指向的目标；R5 又明令留痕不得写在被删对象自身。这不是漏加约束，而是这张表
 * 得以存在的前提——详见 V10 迁移注释。
 */
@Entity
@Table(name = "project_deletion_record")
public class ProjectDeletionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 32)
    private DeletedResourceType resourceType;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @Column(name = "detail", length = 500)
    private String detail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProjectDeletionRecord() {
    }

    public ProjectDeletionRecord(Long projectId, DeletedResourceType resourceType, Long resourceId,
            Long actorUserId, String detail) {
        this.projectId = projectId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.actorUserId = actorUserId;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public DeletedResourceType getResourceType() {
        return resourceType;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
