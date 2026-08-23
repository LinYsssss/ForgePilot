package com.forgepilot.review;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * 针对**一组**输入的一次审查：处于某个 head SHA 的 PR、一个特定的 diff 指纹，
 * 对照一个特定的需求修订。
 *
 * <p>这四个列构成该 Review 的<strong>身份</strong>（ARCHITECTURE.md 3.1），
 * 数据库拒绝它们在创建之后发生变化——改动其中任何一个，都会把这一行变成
 * 另一个 Review。这也正是旧 Review 从不被覆盖的原因：新的 head、变化的 diff、
 * 新发布的需求修订，都会铸造出一个新身份、从而是一行新记录，
 * 而此前那一行则原封不动地保持它当初被裁定时的样子。
 *
 * <p>这次 Review 是否仍适用于该 PR 的当前输入，是把这四个列与 PR 比对后
 * <strong>推导</strong>出来的，并不存储。这里没有 {@code INVALIDATED} 状态：
 * 执行状态与语义有效性是两个不同的维度。
 *
 * <p>那组围栏三元组（{@code executionAttempt}、{@code executionToken}、
 * {@code leaseUntil}）的存在，使执行恢复不需要任何任务表。抢占是一次原子的
 * 条件更新；worker 此后的每一次写入都必须同时匹配这三者，
 * 因此租约已过期的 worker 既不能完成、不能置失败、不能续租，也不能插入 Finding。
 */
@Entity
@Table(name = "review")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "pull_request_id", nullable = false, updatable = false)
    private Long pullRequestId;

    @Column(name = "head_sha", nullable = false, length = 64, updatable = false)
    private String headSha;

    @Column(name = "review_input_fingerprint", nullable = false, length = 64, updatable = false)
    private String reviewInputFingerprint;

    /**
     * 当且仅当 {@link #requirementRevisionId} 为 null 时它才为 null——
     * 也就是该 PR 没有任何需求关联。这个配对关系由数据库 CHECK 强制，
     * 而正是那条 CHECK 让三列外键成为承重结构：在 MATCH SIMPLE 之下，
     * 只要任一列为 null，整个外键就会被完全跳过。
     */
    @Column(name = "requirement_id", updatable = false)
    private Long requirementId;

    @Column(name = "requirement_revision_id", updatable = false)
    private Long requirementRevisionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReviewStatus status = ReviewStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 16)
    private ReviewDecision decision = ReviewDecision.PENDING;

    @Column(name = "decision_by")
    private Long decisionBy;

    @Column(name = "decision_at")
    private Instant decisionAt;

    @Column(name = "decision_comment")
    private String decisionComment;

    @Column(name = "execution_attempt", nullable = false)
    private int executionAttempt;

    @Column(name = "execution_token")
    private UUID executionToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    /**
     * 不可变的输入快照：需求与 AC 文本、带哈希的知识片段，以及截断清单。
     * 历史页面读的是它，而不是该 PR 当前的关联关系，
     * 因此日后改动关联无法改写一次过往审查当初的含义。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_snapshot_json", updatable = false)
    private String contextSnapshotJson;

    /** 输出摘要：逐条 AC 的裁定与覆盖情况。与输入快照分开存放。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary_json")
    private String summaryJson;

    @Column(name = "engine", length = 64)
    private String engine;

    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @Column(name = "model", length = 128)
    private String model;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Review() {
    }

    public Review(Long projectId, Long pullRequestId, String headSha, String reviewInputFingerprint,
            Long requirementId, Long requirementRevisionId) {
        this.projectId = projectId;
        this.pullRequestId = pullRequestId;
        this.headSha = headSha;
        this.reviewInputFingerprint = reviewInputFingerprint;
        this.requirementId = requirementId;
        this.requirementRevisionId = requirementRevisionId;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getPullRequestId() {
        return pullRequestId;
    }

    public String getHeadSha() {
        return headSha;
    }

    public String getReviewInputFingerprint() {
        return reviewInputFingerprint;
    }

    public Long getRequirementId() {
        return requirementId;
    }

    public Long getRequirementRevisionId() {
        return requirementRevisionId;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public ReviewDecision getDecision() {
        return decision;
    }

    public Long getDecisionBy() {
        return decisionBy;
    }

    public Instant getDecisionAt() {
        return decisionAt;
    }

    public String getDecisionComment() {
        return decisionComment;
    }

    public int getExecutionAttempt() {
        return executionAttempt;
    }

    public UUID getExecutionToken() {
        return executionToken;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public String getContextSnapshotJson() {
        return contextSnapshotJson;
    }

    public String getSummaryJson() {
        return summaryJson;
    }

    public String getEngine() {
        return engine;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getModel() {
        return model;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 只在创建时写入一次，且与存储该行处于同一个事务内。 */
    public void recordContextSnapshot(String contextSnapshotJson) {
        this.contextSnapshotJson = contextSnapshotJson;
    }
}
