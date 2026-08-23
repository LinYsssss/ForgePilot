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
 * PR 与需求关联变更的审计记录，与变更本身写在同一个事务里（D007）。
 *
 * <p>本表只记录**变化**，因此两侧都为空、或两侧相等的行会被 CHECK 拒绝。
 * 有两个生产者通过同一张表写入：入库时的自动 {@code REQ-<n>} 关联写
 * {@code SYSTEM} 行，人工纠正写点名了操作账号的 {@code USER} 行。
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

    /** 入库时的自动关联：没有人在操作，因此 actor 为 null，类型为 SYSTEM。 */
    static PullRequestRequirementEvent systemLink(Long projectId, Long pullRequestId, Long toRequirementId,
            String reason) {
        return new PullRequestRequirementEvent(projectId, pullRequestId, null, toRequirementId,
                ScmActorType.SYSTEM, null, reason);
    }

    /**
     * 有人纠正了关联（PRD P1、D007）。{@code actorUserId} 的必填是由实践而非
     * 本方法签名保证的：表上的 CHECK 会拒绝没有 actor 的 USER 行，
     * 因此匿名的人工纠正根本存不进去。任意一侧都可以为 null——清除关联和其他
     * 纠正一样是纠正——但不能两侧都为 null，也不能两侧相等。
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
