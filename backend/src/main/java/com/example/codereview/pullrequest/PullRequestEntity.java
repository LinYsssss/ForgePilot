package com.example.codereview.pullrequest;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "pull_request")
public class PullRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long repositoryId;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(length = 128)
    private String externalPrId;

    private Integer prNumber;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 128)
    private String authorName;

    @Column(nullable = false, length = 128)
    private String sourceBranch;

    @Column(nullable = false, length = 128)
    private String targetBranch;

    @Column(nullable = false, length = 80)
    private String baseSha;

    @Column(nullable = false, length = 80)
    private String headSha;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false, length = 32)
    private String reviewState;

    private Instant lastSyncedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected PullRequestEntity() {
    }

    public PullRequestEntity(Long projectId, Long repositoryId, String provider, String externalPrId,
                             Integer prNumber, String title, String authorName, String sourceBranch,
                             String targetBranch, String baseSha, String headSha) {
        this.projectId = projectId;
        this.repositoryId = repositoryId;
        this.provider = blankToDefault(provider, "GITHUB");
        this.externalPrId = blankToNull(externalPrId);
        this.prNumber = prNumber;
        this.title = title;
        this.authorName = blankToNull(authorName);
        this.sourceBranch = sourceBranch;
        this.targetBranch = targetBranch;
        this.baseSha = baseSha;
        this.headSha = headSha;
        this.status = "OPEN";
        this.reviewState = "PENDING";
        this.lastSyncedAt = Instant.now();
        this.createdAt = this.lastSyncedAt;
        this.updatedAt = this.lastSyncedAt;
    }

    public void update(Integer prNumber, String title, String authorName, String sourceBranch, String targetBranch,
                       String baseSha, String headSha, String provider, String externalPrId, String status) {
        boolean headChanged = !this.headSha.equals(headSha);
        this.prNumber = prNumber;
        this.provider = blankToDefault(provider, this.provider);
        this.externalPrId = blankToNull(externalPrId);
        this.title = title;
        this.authorName = blankToNull(authorName);
        this.sourceBranch = sourceBranch;
        this.targetBranch = targetBranch;
        this.baseSha = baseSha;
        this.headSha = headSha;
        this.status = blankToDefault(status, this.status);
        if (headChanged) {
            this.reviewState = "PENDING";
        }
        this.lastSyncedAt = Instant.now();
        this.updatedAt = this.lastSyncedAt;
    }

    public void applyReviewState(String actionType) {
        this.reviewState = switch (actionType) {
            case "APPROVE" -> "PASSED";
            case "REQUEST_CHANGES" -> "CHANGES_REQUESTED";
            case "WAIVE" -> "WAIVED";
            default -> this.reviewState;
        };
        this.updatedAt = Instant.now();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getRepositoryId() {
        return repositoryId;
    }

    public String getProvider() {
        return provider;
    }

    public String getExternalPrId() {
        return externalPrId;
    }

    public Integer getPrNumber() {
        return prNumber;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthorName() {
        return authorName;
    }

    public String getSourceBranch() {
        return sourceBranch;
    }

    public String getTargetBranch() {
        return targetBranch;
    }

    public String getBaseSha() {
        return baseSha;
    }

    public String getHeadSha() {
        return headSha;
    }

    public String getStatus() {
        return status;
    }

    public String getReviewState() {
        return reviewState;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
