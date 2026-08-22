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
 * One problem a Review reported, plus its lineage across rounds.
 *
 * <p>{@code reviewAttempt} is not bookkeeping. Together with the parent's
 * {@code UNIQUE(project_id, id, execution_attempt)} it forms a composite foreign
 * key that stops a worker holding a stale attempt from inserting here at all —
 * at the database, not in a service check, because the check-then-insert version
 * has a measured window where a dead attempt's finding lands under a live Review.
 *
 * <p>The cost is paid openly: findings left behind by a crashed attempt hold a
 * reference to that attempt number, so re-claiming must delete them in the same
 * transaction or the Review can never be recovered.
 *
 * <p>{@link #status} and {@link #continuity} are orthogonal and must stay in
 * separate fields — one is what a person decided, the other is where this
 * finding came from. Reopening a suppressed finding keeps its continuity: the
 * lineage is a fact about history and does not change because the status did.
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
     * Must equal the parent Review's, compared NULL-safely. A constraint trigger
     * enforces it: nullable composite foreign keys are MATCH SIMPLE and therefore
     * cannot express "same as the parent, including when both are absent".
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

    /** Case sensitive, never lower-cased: it takes part in {@link #findingKey}. */
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
     * Covers deterministic source evidence only. Newlines are normalized and
     * volatile line numbers removed, but indentation is never generally collapsed —
     * Python and YAML mean different things at different indents. Model prose is
     * excluded, or a suppression would drift with the model's wording.
     */
    @Column(name = "evidence_hash", nullable = false, length = 64, updatable = false)
    private String evidenceHash;

    /** Covers the cited AC/revision content, knowledge excerpt hashes and the rule version. */
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
     * Only for a finding created as an inherited suppression, which starts life
     * already rejected. Every other status move goes through a conditional update
     * in the service, so that the audit row's recorded {@code from} is the status
     * the update actually matched rather than one read a moment earlier.
     */
    public void startSuppressed() {
        this.status = FindingStatus.REJECTED;
    }
}
