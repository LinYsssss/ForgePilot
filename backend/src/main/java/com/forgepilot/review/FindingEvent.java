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
 * One human decision about a Finding, kept forever.
 *
 * <p>{@code actorId} points at {@code user_account} rather than
 * {@code project_member}: leaving a project revokes live permissions but must not
 * erase an accomplished fact. {@code Finding.assigneeId} points the other way for
 * the opposite reason.
 *
 * <p>{@link #fromStatus} is taken from the conditional update that performed the
 * move, never from a status read beforehand. Under concurrency the read version
 * lets two events both claim to have started from the same status, which makes
 * the audit trail describe a history that did not happen.
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
