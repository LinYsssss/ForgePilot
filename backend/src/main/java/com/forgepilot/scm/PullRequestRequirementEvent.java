package com.forgepilot.scm;

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

/**
 * The audit of pull request to requirement association changes, written in the
 * same transaction as the change itself (D007).
 *
 * <p>The table records changes only, so a row with neither side or with both sides
 * equal is refused by a CHECK. Two producers write it through the same table: the
 * automatic {@code REQ-<n>} link at ingestion writes a {@code SYSTEM} row, and a
 * human correction writes a {@code USER} row naming the account that made it.
 */
@Entity
@Table(name = "pull_request_requirement_event")
public class PullRequestRequirementEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "pull_request_id", nullable = false)
    private Long pullRequestId;

    @Column(name = "from_requirement_id")
    private Long fromRequirementId;

    @Column(name = "to_requirement_id")
    private Long toRequirementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 16)
    private ScmActorType actorType;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "reason")
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PullRequestRequirementEvent() {
    }

    private PullRequestRequirementEvent(Long projectId, Long pullRequestId, Long fromRequirementId,
            Long toRequirementId, ScmActorType actorType, Long actorUserId, String reason) {
        this.projectId = projectId;
        this.pullRequestId = pullRequestId;
        this.fromRequirementId = fromRequirementId;
        this.toRequirementId = toRequirementId;
        this.actorType = actorType;
        this.actorUserId = actorUserId;
        this.reason = reason;
    }

    /** The automatic link at ingestion: no human acted, so the actor is null and the type is SYSTEM. */
    static PullRequestRequirementEvent systemLink(Long projectId, Long pullRequestId, Long toRequirementId,
            String reason) {
        return new PullRequestRequirementEvent(projectId, pullRequestId, null, toRequirementId,
                ScmActorType.SYSTEM, null, reason);
    }

    /**
     * A person corrected the association (PRD P1, D007). {@code actorUserId} is
     * mandatory in practice, not by this signature: the CHECK on the table refuses
     * a USER row without one, so an anonymous human correction cannot be stored.
     * Either side may be null — clearing the link is a correction like any other —
     * but not both, and not two equal sides.
     */
    static PullRequestRequirementEvent userCorrection(Long projectId, Long pullRequestId,
            Long fromRequirementId, Long toRequirementId, Long actorUserId, String reason) {
        return new PullRequestRequirementEvent(projectId, pullRequestId, fromRequirementId,
                toRequirementId, ScmActorType.USER, actorUserId, reason);
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

    public Long getFromRequirementId() {
        return fromRequirementId;
    }

    public Long getToRequirementId() {
        return toRequirementId;
    }

    public ScmActorType getActorType() {
        return actorType;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
