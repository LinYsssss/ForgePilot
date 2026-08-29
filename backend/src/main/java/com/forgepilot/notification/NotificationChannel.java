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

    /** {@code null} 表示这个渠道<strong>不加签</strong>，与「密钥是空串」不是一回事。 */
    @Column(name = "encrypted_secret")
    private String encryptedSecret;

    /**
     * 机器人「自定义关键词」安全设置里配的那个词。{@code null} 表示不需要。
     *
     * <p>它<strong>不是凭据</strong>，因此不加密、也照常返回给配置者——配错了必须能看出来，
     * 否则就只能靠「消息没到」来猜。
     */
    @Column(name = "keyword", length = 64)
    private String keyword;

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
     *
     * <p>{@code encryptedSecret} 传 {@code null} 表示这个渠道不加签。它必须能被显式地
     * 从「有」改成「没有」——否则一个曾经配过密钥的渠道，就再也回不到不加签那一档。
     */
    void replaceCredentials(String encryptedWebhookUrl, String encryptedSecret) {
        this.encryptedWebhookUrl = encryptedWebhookUrl;
        this.encryptedSecret = encryptedSecret;
    }

    void enable(boolean enabled) {
        this.enabled = enabled;
    }

    void useKeyword(String keyword) {
        this.keyword = keyword;
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

    String getKeyword() {
        return keyword;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
