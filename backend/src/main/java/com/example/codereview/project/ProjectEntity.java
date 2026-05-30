package com.example.codereview.project;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "project")
public class ProjectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 128)
    private String defaultBranch;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ProjectEntity() {
    }

    public ProjectEntity(Long ownerId, String name, String description, String defaultBranch) {
        this.ownerId = ownerId;
        this.name = name;
        this.description = description;
        this.defaultBranch = defaultBranch == null || defaultBranch.isBlank() ? "main" : defaultBranch;
        this.status = "ACTIVE";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void update(String name, String description, String defaultBranch) {
        this.name = name;
        this.description = description;
        this.defaultBranch = defaultBranch;
        this.updatedAt = Instant.now();
    }
}
