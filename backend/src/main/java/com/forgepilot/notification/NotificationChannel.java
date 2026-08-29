package com.forgepilot.notification;

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
 * 一个项目在某个渠道上的通知配置。
 *
 * <p>两个凭据列存的都是密文，且<strong>永远不会</strong>出现在任何响应体里。webhook URL
 * 之所以也按凭据对待，是因为钉钉机器人的 {@code access_token} 就写在这个 URL 的查询串里
 * ——拿到 URL 就等于拿到了往那个群发消息的权限。
 */
@Entity
@Table(name = "project_notification_channel")
public class NotificationChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 32)
    private NotificationChannelType channel;

    @Column(name = "encrypted_webhook_url", nullable = false)
    private String encryptedWebhookUrl;

    @Column(name = "encrypted_secret", nullable = false)
    private String encryptedSecret;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationChannel() {
    }

    NotificationChannel(long projectId, NotificationChannelType channel, String encryptedWebhookUrl,
            String encryptedSecret) {
        this.projectId = projectId;
        this.channel = channel;
        this.encryptedWebhookUrl = encryptedWebhookUrl;
        this.encryptedSecret = encryptedSecret;
        this.enabled = true;
    }

    /**
     * 换一组凭据。两个值一起换，不提供只换其中一个的路径：URL 与加签密钥出自钉钉后台的
     * 同一次配置，分开更新会留下一组对不上的组合，而那种失败只会在下一次推送时才暴露。
     */
    void replaceCredentials(String encryptedWebhookUrl, String encryptedSecret) {
        this.encryptedWebhookUrl = encryptedWebhookUrl;
        this.encryptedSecret = encryptedSecret;
    }

    void enable(boolean enabled) {
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public NotificationChannelType getChannel() {
        return channel;
    }

    String getEncryptedWebhookUrl() {
        return encryptedWebhookUrl;
    }

    String getEncryptedSecret() {
        return encryptedSecret;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
