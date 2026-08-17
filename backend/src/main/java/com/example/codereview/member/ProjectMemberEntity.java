package com.example.codereview.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "project_member",
        uniqueConstraints = @UniqueConstraint(name = "uq_project_member", columnNames = {"projectId", "userId"}))
public class ProjectMemberEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String role;

    @Column(nullable = false)
    private Instant createdAt;

    protected ProjectMemberEntity() {
    }

    public ProjectMemberEntity(Long projectId, Long userId, ProjectRole role) {
        this.projectId = projectId;
        this.userId = userId;
        this.role = role.name();
        this.createdAt = Instant.now();
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
        return ProjectRole.fromName(role);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void changeRole(ProjectRole role) {
        this.role = role.name();
    }
}
