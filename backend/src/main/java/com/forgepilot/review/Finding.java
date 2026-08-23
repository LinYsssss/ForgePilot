package com.forgepilot.review;

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
 * 一次 Review 报告出的一个问题，连同它跨轮次的血缘。
 *
 * <p>{@code reviewAttempt} 不是记账字段。它与父行的
 * {@code UNIQUE(project_id, id, execution_attempt)} 一起构成复合外键，
 * 从根本上阻止持有过期 attempt 的 worker 往这里插入——是在**数据库**层面，
 * 而不是服务层检查，因为「先检查再插入」的版本有一个实测存在的窗口，
 * 会让一个已死 attempt 的 finding 落到一个活着的 Review 之下。
 *
 * <p>代价是明码标价的：崩溃的 attempt 遗留下来的 finding 持有对那个 attempt
 * 编号的引用，因此重新抢占时必须在同一个事务里删掉它们，
 * 否则这个 Review 将永远无法恢复。
 *
 * <p>{@link #status} 与 {@link #continuity} 是正交的，必须分作两个字段——
 * 一个是人做了什么判断，另一个是这条问题从哪来。重开一个被抑制的 finding
 * 会保留它的血缘：血缘是关于历史的事实，不会因为状态变了就跟着变。
 */
@Entity
@Table(name = "finding")
public class Finding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "review_id", nullable = false, updatable = false)
    private Long reviewId;

    @Column(name = "review_attempt", nullable = false, updatable = false)
    private int reviewAttempt;

    /**
     * 必须与父 Review 的值相等，且比较是 NULL 安全的。由约束触发器强制：
     * 可空的复合外键是 MATCH SIMPLE，因此表达不了
     * “与父行相同，包括两者都缺席的情形”。
     */
    @Column(name = "requirement_id", updatable = false)
    private Long requirementId;

    @Column(name = "requirement_revision_id", updatable = false)
    private Long requirementRevisionId;

    @Column(name = "ac_id", updatable = false)
    private Long acId;

    @Enumerated(EnumType.STRING)
    @Column(name = "finding_type", nullable = false, length = 32, updatable = false)
    private FindingType findingType;

    /** 大小写敏感，绝不转小写：它参与构成 {@link #findingKey}。 */
    @Column(name = "path", updatable = false)
    private String path;

    @Column(name = "line", updatable = false)
    private Integer line;

    @Column(name = "evidence", updatable = false)
    private String evidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private FindingStatus status = FindingStatus.OPEN;

    @Column(name = "assignee_id")
    private Long assigneeId;

    @Column(name = "finding_key", nullable = false, length = 255, updatable = false)
    private String findingKey;

    /**
     * 只覆盖确定性的源码证据。会归一化换行、去掉易变的行号，
     * 但绝不做通用的缩进折叠——Python 和 YAML 在不同缩进下含义不同。
     * 模型生成的散文被排除在外，否则抑制项会随模型的措辞漂移。
     */
    @Column(name = "evidence_hash", nullable = false, length = 64, updatable = false)
    private String evidenceHash;

    /** 覆盖被引用的 AC/修订内容、知识片段哈希以及规则版本。 */
    @Column(name = "basis_hash", nullable = false, length = 64, updatable = false)
    private String basisHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "continuity", nullable = false, length = 16, updatable = false)
    private FindingContinuity continuity;

    @Column(name = "carried_from_finding_id", updatable = false)
    private Long carriedFromFindingId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Finding() {
    }

    public Finding(Long projectId, Long reviewId, int reviewAttempt, Long requirementId,
            Long requirementRevisionId, Long acId, FindingType findingType, String path, Integer line,
            String evidence, String findingKey, String evidenceHash, String basisHash,
            FindingContinuity continuity, Long carriedFromFindingId) {
        this.projectId = projectId;
        this.reviewId = reviewId;
        this.reviewAttempt = reviewAttempt;
        this.requirementId = requirementId;
        this.requirementRevisionId = requirementRevisionId;
        this.acId = acId;
        this.findingType = findingType;
        this.path = path;
        this.line = line;
        this.evidence = evidence;
        this.findingKey = findingKey;
        this.evidenceHash = evidenceHash;
        this.basisHash = basisHash;
        this.continuity = continuity;
        this.carriedFromFindingId = carriedFromFindingId;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getReviewId() {
        return reviewId;
    }

    public int getReviewAttempt() {
        return reviewAttempt;
    }

    public Long getRequirementId() {
        return requirementId;
    }

    public Long getRequirementRevisionId() {
        return requirementRevisionId;
    }

    public Long getAcId() {
        return acId;
    }

    public FindingType getFindingType() {
        return findingType;
    }

    public String getPath() {
        return path;
    }

    public Integer getLine() {
        return line;
    }

    public String getEvidence() {
        return evidence;
    }

    public FindingStatus getStatus() {
        return status;
    }

    public Long getAssigneeId() {
        return assigneeId;
    }

    public String getFindingKey() {
        return findingKey;
    }

    public String getEvidenceHash() {
        return evidenceHash;
    }

    public String getBasisHash() {
        return basisHash;
    }

    public FindingContinuity getContinuity() {
        return continuity;
    }

    public Long getCarriedFromFindingId() {
        return carriedFromFindingId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 仅用于作为「被继承的抑制项」创建的 finding——它一出生就已是被驳回状态。
     * 其余所有状态变动都走服务层的条件更新，
     * 使审计行里记录的 {@code from} 是那次更新**实际匹配到**的状态，
     * 而不是片刻之前读到的某个值。
     */
    public void startSuppressed() {
        this.status = FindingStatus.REJECTED;
    }
}
