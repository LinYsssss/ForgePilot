package com.example.codereview.agent.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.Instant;

@Entity
@Table(
        name = "agent_outbox_event",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_agent_outbox_event_key",
                columnNames = "eventKey"
        ),
        indexes = {
                @Index(
                        name = "idx_agent_outbox_available",
                        columnList = "status,nextAttemptAt,createdAt"
                ),
                @Index(name = "idx_agent_outbox_run", columnList = "agentRunId")
        }
)
public class AgentOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200, updatable = false)
    private String eventKey;

    @Column(nullable = false, updatable = false)
    private Long agentRunId;

    @Column(nullable = false, length = 60, updatable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "text", updatable = false)
    private String payload;

    @Column(length = 128, updatable = false)
    private String traceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AgentOutboxStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    private Instant claimedAt;

    /**
     * 标识当前持有本事件的 worker。每一次回写都以它为条件,
     * 因此租约已过期的 worker 无法覆盖掉后来回收者写下的结果。
     */
    @Column(length = 64)
    private String claimToken;

    /** 当前认领何时失效、回收器可以把事件重新排队的时刻。 */
    private Instant leaseExpiresAt;

    private Instant sentAt;

    /** 重试耗尽时置位;此后该事件即为终态。 */
    private Instant failedAt;

    @Column(columnDefinition = "text")
    private String lastError;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected AgentOutboxEvent() {
    }

    private AgentOutboxEvent(
            String eventKey,
            Long agentRunId,
            String eventType,
            String payload,
            String traceId,
            Instant now
    ) {
        this.eventKey = requireText(eventKey, "eventKey");
        this.agentRunId = java.util.Objects.requireNonNull(agentRunId, "agentRunId");
        this.eventType = requireText(eventType, "eventType");
        this.payload = java.util.Objects.requireNonNull(payload, "payload");
        this.traceId = traceId;
        this.status = AgentOutboxStatus.PENDING;
        this.nextAttemptAt = java.util.Objects.requireNonNull(now, "now");
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static AgentOutboxEvent pending(
            String eventKey,
            Long agentRunId,
            String eventType,
            String payload,
            String traceId,
            Instant now
    ) {
        return new AgentOutboxEvent(eventKey, agentRunId, eventType, payload, traceId, now);
    }

    // 状态流转刻意放在 AgentOutboxRepository 里做成带条件的批量更新,而不是写成实体的 setter:
    // 每一次流转都必须是针对 (id, claim_token, status) 的 compare-and-set,
    // 而「读出来—改—存回去」这套往返根本表达不了这个语义。

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public String getEventKey() {
        return eventKey;
    }

    public Long getAgentRunId() {
        return agentRunId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public String getTraceId() {
        return traceId;
    }

    public AgentOutboxStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public String getClaimToken() {
        return claimToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
