package com.forgepilot.scm;

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

@Entity
@Table(name = "project_member_scm_binding")
class ProjectMemberScmBinding {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "project_id", nullable = false) private Long projectId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "scm_identity_id", nullable = false) private Long scmIdentityId;
    @Column(name = "repository_id") private Long repositoryId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32) private Status status;
    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", length = 16) private AccessLevel accessLevel;
    @Column(name = "access_checked_at") private Instant accessCheckedAt;
    @Column(name = "requested_by") private Long requestedBy;
    @Column(name = "approved_by") private Long approvedBy;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "activated_at") private Instant activatedAt;
    @Column(name = "ended_at") private Instant endedAt;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ProjectMemberScmBinding() {}

    ProjectMemberScmBinding(long projectId, long userId, long identityId, long repositoryId,
            AccessLevel accessLevel, Instant checkedAt, boolean approvalRequired) {
        this.projectId = projectId;
        this.userId = userId;
        this.scmIdentityId = identityId;
        this.repositoryId = repositoryId;
        this.accessLevel = accessLevel;
        this.accessCheckedAt = checkedAt;
        this.requestedBy = userId;
        this.requestedAt = checkedAt;
        this.status = approvalRequired ? Status.PENDING_APPROVAL : Status.ACTIVE;
        this.activatedAt = approvalRequired ? null : checkedAt;
    }

    Long getId() { return id; }
    Long getProjectId() { return projectId; }
    Long getUserId() { return userId; }
    Long getScmIdentityId() { return scmIdentityId; }
    Status getStatus() { return status; }
    AccessLevel getAccessLevel() { return accessLevel; }
    Instant getAccessCheckedAt() { return accessCheckedAt; }
    Long getApprovedBy() { return approvedBy; }
    Instant getRequestedAt() { return requestedAt; }
    Instant getDecidedAt() { return decidedAt; }
    Instant getActivatedAt() { return activatedAt; }
    Instant getEndedAt() { return endedAt; }

    void supersede(Instant now) { status = Status.SUPERSEDED; endedAt = now; }
    void approve(long leaderId, Instant now) {
        status = Status.ACTIVE; approvedBy = leaderId; decidedAt = now; activatedAt = now;
    }
    void reject(long leaderId, Instant now) {
        status = Status.REJECTED; approvedBy = leaderId; decidedAt = now; endedAt = now;
    }
    void revoke(Instant now) { status = Status.REVOKED; endedAt = now; }

    enum Status { PENDING_APPROVAL, ACTIVE, REJECTED, REVOKED, SUPERSEDED, LEGACY_UNCONFIRMED }
    enum AccessLevel { READ, WRITE, ADMIN }
}
