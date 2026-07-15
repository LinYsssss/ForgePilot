package com.example.codereview.scm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * A single received webhook delivery.
 *
 * <p>Serves two jobs: idempotency (the unique {@code (provider, deliveryId)} key collapses replays)
 * and audit. To keep private repository payloads out of the database, only a SHA-256
 * {@link #payloadHash} and a bounded, sanitized {@link #payloadPreview} are stored — never the
 * unrestricted raw body. The preview is capped at {@link #PREVIEW_MAX_LENGTH} characters by
 * {@link #setPayloadPreview(String)} so an oversized caller value cannot defeat the bound.
 */
@Entity
@Table(name = "scm_webhook_delivery",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_scm_delivery_provider_delivery",
                columnNames = {"provider", "delivery_id"}))
public class WebhookDelivery {

    /** Upper bound, in characters, for the stored {@link #payloadPreview}. */
    public static final int PREVIEW_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private ScmProviderType provider;

    /** {@code X-GitHub-Delivery}, GitLab event UUID, or a deterministic fallback key. */
    @Column(name = "delivery_id", nullable = false, length = 255)
    private String deliveryId;

    /** Resolved installation; null while {@link WebhookDeliveryStatus#RECEIVED} or when rejected. */
    @Column(name = "installation_id")
    private Long installationId;

    @Column(name = "event_type", length = 64)
    private String eventType;

    @Column(name = "action", length = 64)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private WebhookDeliveryStatus status;

    @Column(name = "payload_hash", length = 80)
    private String payloadHash;

    @Column(name = "payload_preview", length = PREVIEW_MAX_LENGTH)
    private String payloadPreview;

    /** Agent Run produced by this delivery, once processed. */
    @Column(name = "agent_run_id")
    private Long agentRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Bounds a raw preview string to {@link #PREVIEW_MAX_LENGTH} characters. */
    public static String boundedPreview(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.length() <= PREVIEW_MAX_LENGTH ? raw : raw.substring(0, PREVIEW_MAX_LENGTH);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ScmProviderType getProvider() {
        return provider;
    }

    public void setProvider(ScmProviderType provider) {
        this.provider = provider;
    }

    public String getDeliveryId() {
        return deliveryId;
    }

    public void setDeliveryId(String deliveryId) {
        this.deliveryId = deliveryId;
    }

    public Long getInstallationId() {
        return installationId;
    }

    public void setInstallationId(Long installationId) {
        this.installationId = installationId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public WebhookDeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(WebhookDeliveryStatus status) {
        this.status = status;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public String getPayloadPreview() {
        return payloadPreview;
    }

    public void setPayloadPreview(String payloadPreview) {
        this.payloadPreview = boundedPreview(payloadPreview);
    }

    public Long getAgentRunId() {
        return agentRunId;
    }

    public void setAgentRunId(Long agentRunId) {
        this.agentRunId = agentRunId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
