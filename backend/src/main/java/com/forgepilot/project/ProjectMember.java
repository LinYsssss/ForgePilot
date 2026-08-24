package com.forgepilot.project;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 某个账号在某个项目中的成员关系。角色集合由独立关系表持久化；SCM 身份
 * 归 {@code scm} 模块中的用户身份与项目绑定所有。
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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "project_member_role", joinColumns = {
            @JoinColumn(name = "project_id", referencedColumnName = "project_id"),
            @JoinColumn(name = "user_id", referencedColumnName = "user_id")
    })
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private Set<ProjectRole> roles = new LinkedHashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProjectMember() {
    }

    public ProjectMember(Long projectId, Long userId, Collection<ProjectRole> roles) {
        this.projectId = projectId;
        this.userId = userId;
        replaceRoles(roles);
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

    public Set<ProjectRole> getRoles() {
        return Set.copyOf(roles);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean hasRole(ProjectRole role) {
        return roles.contains(role);
    }

    public void replaceRoles(Collection<ProjectRole> newRoles) {
        if (newRoles == null || newRoles.isEmpty()) {
            throw new IllegalArgumentException("A project member needs at least one role.");
        }
        this.roles.clear();
        this.roles.addAll(newRoles);
    }

    public void addRole(ProjectRole role) {
        this.roles.add(role);
    }

    public void removeRole(ProjectRole role) {
        this.roles.remove(role);
    }
}
