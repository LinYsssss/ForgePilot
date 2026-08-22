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
 * One review of one set of inputs: a pull request at a head SHA, with a specific
 * diff fingerprint, against a specific requirement revision.
 *
 * <p>Those four columns are the Review's <strong>identity</strong>
 * (ARCHITECTURE.md 3.1) and the database refuses to let them change after
 * creation — altering one would turn this row into a different Review. That is
 * also why old Reviews are never overwritten: a new head, a changed diff or a
 * newly published requirement revision all mint a new identity and therefore a
 * new row, and the previous one stays exactly as it was decided.
 *
 * <p>Whether this Review still applies to the pull request's current inputs is
 * <strong>derived</strong> by comparing those four columns against the pull
 * request, not stored. There is no {@code INVALIDATED} status: execution state
 * and semantic validity are different dimensions.
 *
 * <p>The fencing triple ({@code executionAttempt}, {@code executionToken},
 * {@code leaseUntil}) exists so execution recovery needs no task table. Claiming
 * is one atomic conditional update; every later write by a worker must match all
 * of them, so a worker whose lease expired can neither finish, fail, renew nor
 * insert a Finding.
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
     * Null exactly when {@link #requirementRevisionId} is null — the pull request
     * carries no requirement association. A database CHECK enforces the pairing,
     * and that CHECK is what makes the three-column foreign key load-bearing:
     * under MATCH SIMPLE the key is skipped entirely if either column is null.
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
     * The immutable input snapshot: requirement and AC text, knowledge excerpts
     * with their hashes, and the truncation manifest. History pages read this
     * rather than the pull request's current association, so a later association
     * change cannot rewrite what a past review meant.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_snapshot_json", updatable = false)
    private String contextSnapshotJson;

    /** The output summary: per-AC verdicts and coverage. Kept apart from the input snapshot. */
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

    /** Written once, at creation, inside the same transaction that stores the row. */
    public void recordContextSnapshot(String contextSnapshotJson) {
        this.contextSnapshotJson = contextSnapshotJson;
    }
}
