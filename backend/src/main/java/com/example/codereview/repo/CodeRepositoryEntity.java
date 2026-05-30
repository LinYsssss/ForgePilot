package com.example.codereview.repo;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "code_repository")
public class CodeRepositoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 512)
    private String repoUrl;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(nullable = false, length = 128)
    private String defaultBranch;

    @Column(columnDefinition = "text")
    private String accessToken;

    @Column(length = 512)
    private String localPath;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(columnDefinition = "text")
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected CodeRepositoryEntity() {
    }

    public CodeRepositoryEntity(Long projectId, String repoUrl, String provider, String defaultBranch, String accessToken) {
        this.projectId = projectId;
        this.repoUrl = repoUrl;
        this.provider = provider == null || provider.isBlank() ? "OTHER" : provider;
        this.defaultBranch = defaultBranch == null || defaultBranch.isBlank() ? "main" : defaultBranch;
        this.accessToken = accessToken;
        this.status = "ACTIVE";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public String getProvider() {
        return provider;
    }

    public String getLocalPath() {
        return localPath;
    }

    public String getAccessTokenCiphertext() {
        return accessToken;
    }

    public String getStatus() {
        return status;
    }

    public void markLocalPath(String localPath) {
        this.localPath = localPath;
        this.updatedAt = Instant.now();
    }

    public void update(String repoUrl, String provider, String defaultBranch, String accessToken) {
        this.repoUrl = repoUrl;
        this.provider = provider == null || provider.isBlank() ? "OTHER" : provider;
        this.defaultBranch = defaultBranch == null || defaultBranch.isBlank() ? "main" : defaultBranch;
        this.accessToken = accessToken;
        this.status = "ACTIVE";
        this.lastError = null;
        this.updatedAt = Instant.now();
    }

    public void markError(String message) {
        this.status = "ERROR";
        this.lastError = message;
        this.updatedAt = Instant.now();
    }
}
