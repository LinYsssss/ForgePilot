package com.example.codereview.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Entity
@Table(
        name = "review_task",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_review_task_idempotency",
                        columnNames = {"projectId", "repositoryId", "commitId", "baseCommitIdNormalized", "branchName"}
                )
        },
        indexes = {
                @Index(name = "idx_review_task_project", columnList = "projectId"),
                @Index(name = "idx_review_task_pull_request", columnList = "pullRequestId")
        }
)
public class ReviewTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long repositoryId;

    @Column(nullable = false, length = 80)
    private String commitId;

    @Column(length = 80)
    private String baseCommitId;

    @Column(nullable = false, length = 80)
    private String baseCommitIdNormalized;

    @Column(nullable = false, length = 128)
    private String branchName;

    @Column(nullable = false)
    private Long triggerUserId;

    private Long pullRequestId;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private int retryCount;

    @Column(columnDefinition = "text")
    private String diffText;

    @Column(columnDefinition = "text")
    private String knowledgeDocIds;

    @Column(columnDefinition = "text")
    private String errorMessage;

    private Instant startedAt;
    private Instant finishedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ReviewTask() {
    }

    public ReviewTask(Long projectId, Long repositoryId, String commitId, String baseCommitId, String branchName, Long triggerUserId, String diffText) {
        this(projectId, repositoryId, commitId, baseCommitId, branchName, triggerUserId, diffText, null);
    }

    public ReviewTask(Long projectId, Long repositoryId, String commitId, String baseCommitId, String branchName, Long triggerUserId, String diffText, List<Long> knowledgeDocIds) {
        this(projectId, repositoryId, commitId, baseCommitId, branchName, triggerUserId, diffText, knowledgeDocIds, null);
    }

    public ReviewTask(Long projectId, Long repositoryId, String commitId, String baseCommitId, String branchName,
                      Long triggerUserId, String diffText, List<Long> knowledgeDocIds, Long pullRequestId) {
        this.projectId = projectId;
        this.repositoryId = repositoryId;
        this.commitId = commitId;
        this.baseCommitId = baseCommitId;
        this.baseCommitIdNormalized = normalizeBaseCommitId(baseCommitId);
        this.branchName = branchName;
        this.triggerUserId = triggerUserId;
        this.pullRequestId = pullRequestId;
        this.diffText = diffText;
        this.knowledgeDocIds = serializeDocIds(knowledgeDocIds);
        this.status = "PENDING";
        this.retryCount = 0;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    private static String serializeDocIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return ids.stream().filter(java.util.Objects::nonNull).map(String::valueOf).collect(Collectors.joining(","));
    }

    private static String normalizeBaseCommitId(String baseCommitId) {
        return baseCommitId == null ? "" : baseCommitId;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getCommitId() {
        return commitId;
    }

    public String getBaseCommitId() {
        return baseCommitId;
    }

    public String getBaseCommitIdNormalized() {
        return baseCommitIdNormalized;
    }

    public String getBranchName() {
        return branchName;
    }

    public String getStatus() {
        return status;
    }

    public Long getPullRequestId() {
        return pullRequestId;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Long getRepositoryId() {
        return repositoryId;
    }

    public String getDiffText() {
        return diffText;
    }

    public List<Long> getKnowledgeDocIds() {
        if (knowledgeDocIds == null || knowledgeDocIds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(knowledgeDocIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void markRunning() {
        if ("RUNNING".equals(this.status)) {
            return;
        }
        this.status = "RUNNING";
        this.startedAt = Instant.now();
        this.updatedAt = this.startedAt;
        this.errorMessage = null;
    }

    public void markSuccess() {
        this.status = "SUCCESS";
        this.finishedAt = Instant.now();
        this.updatedAt = this.finishedAt;
    }

    public void markFailed(String message) {
        this.status = "FAILED";
        this.retryCount++;
        this.errorMessage = message;
        this.finishedAt = Instant.now();
        this.updatedAt = this.finishedAt;
    }

    public void markDead(String message) {
        this.status = "DEAD";
        this.errorMessage = message;
        this.finishedAt = Instant.now();
        this.updatedAt = this.finishedAt;
    }

    public void markCanceled() {
        this.status = "CANCELED";
        this.finishedAt = Instant.now();
        this.updatedAt = this.finishedAt;
    }

    public boolean isCanceled() {
        return "CANCELED".equals(this.status);
    }

    public boolean isTerminal() {
        return "SUCCESS".equals(this.status) || "DEAD".equals(this.status) || "CANCELED".equals(this.status);
    }
}
