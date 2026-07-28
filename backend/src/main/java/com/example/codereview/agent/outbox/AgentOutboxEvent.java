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
     * Identifies the worker that currently holds this event. Every write-back is conditional on it,
     * so a worker whose lease expired cannot overwrite the result of whoever reclaimed the event.
     */
    @Column(length = 64)
    private String claimToken;

    /** When the current claim stops being valid and the reaper may requeue the event. */
    private Instant leaseExpiresAt;

    private Instant sentAt;

    /** Set when retries were exhausted; the event is terminal from then on. */
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

    // State transitions deliberately live in AgentOutboxRepository as conditional bulk updates
    // rather than as entity mutators: every one of them has to be a compare-and-set on
    // (id, claim_token, status), and a load-mutate-save round trip cannot express that.

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
