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
                        columnNames = {"projectId", "repositoryId", "commitId", "baseCommitIdNormalized", "branchName", "docSetKey"}
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

    /**
     * 参与审查的知识文档集合指纹(排序去重后 SHA-256;空集为空串)。
     * 纳入幂等唯一键:同一提交携带不同文档组合应产生不同任务
     * ——这是"带/不带知识库对比审查"的基础;相同组合的重复点击仍然幂等。
     */
    @Column(nullable = false, length = 64)
    private String docSetKey;

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

    /** 五臂实验 flags 快照(P4a);null = 生产默认全开。不入幂等键:同一提交换臂复跑走删任务重建。 */
    @Column(length = 512)
    private String flagsJson;

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
        this.docSetKey = computeDocSetKey(knowledgeDocIds);
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

    /** 文档集合指纹:排序去重后逗号串的 SHA-256 hex;空集(含 null)固定为空串。 */
    public static String computeDocSetKey(List<Long> ids) {
        if (ids == null) {
            return "";
        }
        String canonical = ids.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        if (canonical.isEmpty()) {
            return "";
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public String getDocSetKey() {
        return docSetKey;
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

    public String getFlagsJson() {
        return flagsJson;
    }

    /** 建任务时写入一次;消费端只读(异步/复跑都按下单时的组合执行)。 */
    public void applyFlags(String flagsJson) {
        this.flagsJson = flagsJson;
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
