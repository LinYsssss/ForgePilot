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
 * 一次收到的 webhook 投递。
 *
 * <p>承担两件事:幂等(唯一键 {@code (provider, deliveryId)} 把重放收敛成一条)与审计。
 * 为了不把私有仓库的载荷落进数据库,这里只存 SHA-256 的 {@link #payloadHash} 与一段有界、
 * 已脱敏的 {@link #payloadPreview}——绝不存未加限制的原始报文。预览长度由
 * {@link #setPayloadPreview(String)} 截到 {@link #PREVIEW_MAX_LENGTH} 字符,
 * 因此调用方传入超长值也突破不了这个上界。
 */
@Entity
@Table(name = "scm_webhook_delivery",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_scm_delivery_provider_delivery",
                columnNames = {"provider", "delivery_id"}))
public class WebhookDelivery {

    /** 落库的 {@link #payloadPreview} 的字符数上界。 */
    public static final int PREVIEW_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private ScmProviderType provider;

    /** {@code X-GitHub-Delivery}、GitLab 的事件 UUID,或一个确定性的兜底键。 */
    @Column(name = "delivery_id", nullable = false, length = 255)
    private String deliveryId;

    /** 解析出的 installation;处于 {@link WebhookDeliveryStatus#RECEIVED} 或被拒时为 null。 */
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

    /** 本次投递处理完成后产出的 Agent Run。 */
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

    /** 把原始预览串截断到 {@link #PREVIEW_MAX_LENGTH} 字符以内。 */
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
