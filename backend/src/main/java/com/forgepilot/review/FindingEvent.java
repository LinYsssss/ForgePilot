package com.forgepilot.review;

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
 * 关于某个 Finding 的一次人工决定，永久保存。
 *
 * <p>{@code actorId} 指向 {@code user_account} 而非 {@code project_member}：
 * 离开项目会吊销当下的权限，但绝不该抹掉一件已经发生的事实。
 * {@code Finding.assigneeId} 指向相反的方向，理由也正相反。
 *
 * <p>{@link #fromStatus} 取自执行了那次流转的**条件更新**，而绝不取自事前的
 * 一次状态读取。在并发下，「先读后写」的版本会让两个事件都声称自己从同一个
 * 状态出发，于是审计轨迹描述出一段并未发生过的历史。
 */
@Entity
@Table(name = "finding_event")
public class FindingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "finding_id", nullable = false)
    private Long findingId;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private FindingAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 16)
    private FindingStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 16)
    private FindingStatus toStatus;

    @Column(name = "comment")
    private String comment;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FindingEvent() {
    }

    public FindingEvent(Long projectId, Long findingId, Long actorId, FindingAction action,
            FindingStatus fromStatus, FindingStatus toStatus, String comment) {
        this.projectId = projectId;
        this.findingId = findingId;
        this.actorId = actorId;
        this.action = action;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.comment = comment;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getFindingId() {
        return findingId;
    }

    public Long getActorId() {
        return actorId;
    }

    public FindingAction getAction() {
        return action;
    }

    public FindingStatus getFromStatus() {
        return fromStatus;
    }

    public FindingStatus getToStatus() {
        return toStatus;
    }

    public String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
