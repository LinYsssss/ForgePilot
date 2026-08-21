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
 * Membership of one account in one project, plus that project's SCM identity for
 * the member. {@code scmExternalUserId} is the authorization key, {@code scmUsername}
 * is display only and must never drive a permission decision (D010).
 */
@Entity
@Table(name = "project_member")
public class ProjectMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private ProjectRole role;

    @Column(name = "scm_external_user_id", length = 128)
    private String scmExternalUserId;

    @Column(name = "scm_username", length = 128)
    private String scmUsername;

    @Column(name = "scm_identity_verified_at")
    private Instant scmIdentityVerifiedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProjectMember() {
    }

    public ProjectMember(Long projectId, Long userId, ProjectRole role) {
        this.projectId = projectId;
        this.userId = userId;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getUserId() {
        return userId;
    }

    public ProjectRole getRole() {
        return role;
    }

    public String getScmExternalUserId() {
        return scmExternalUserId;
    }

    public String getScmUsername() {
        return scmUsername;
    }

    public Instant getScmIdentityVerifiedAt() {
        return scmIdentityVerifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void changeRole(ProjectRole newRole) {
        this.role = newRole;
    }

    /** Both SCM columns move together: an identity without its display name is meaningless. */
    public void assignScmIdentity(String externalUserId, String username, Instant verifiedAt) {
        this.scmExternalUserId = externalUserId;
        this.scmUsername = username;
        this.scmIdentityVerifiedAt = verifiedAt;
    }
}
