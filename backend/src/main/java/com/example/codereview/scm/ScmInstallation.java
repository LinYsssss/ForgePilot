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
 * 一条 provider 绑定:让 RepoSage 能接收某一个代码托管方的 webhook,并回调它。
 *
 * <p>对 GitHub 而言这是一次 GitHub App 安装(App id + 加密私钥 + webhook 密钥);
 * 对 GitLab v1 而言是一个加密的项目访问令牌加 webhook 密钥。两个凭据列都加密存储
 * (见 {@code CryptoService}),且绝不能被任何读接口返回。installation 是密钥与 API host 的
 * <em>唯一</em>来源——它们只从投递中**已验证的身份**解析得到,绝不信任载荷里自带的字段。
 * 轮换即更新这些加密列;{@code updatedAt} 记录最近一次轮换时间。
 */
@Entity
@Table(name = "scm_installation",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_scm_installation_provider_external",
                columnNames = {"provider", "external_installation_id"}))
public class ScmInstallation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private ScmProviderType provider;

    /** GitHub App 的 installation id,或 GitLab 的 project id——即 provider 自己的标识符。 */
    @Column(name = "external_installation_id", nullable = false, length = 255)
    private String externalInstallationId;

    @Column(name = "display_name", length = 255)
    private String displayName;

    /** GitHub App id(GitLab 场景下为 null)。 */
    @Column(name = "app_id", length = 128)
    private String appId;

    /** 回调允许使用的 API host;只在这里解析,绝不取自 webhook 载荷。 */
    @Column(name = "api_base_url", length = 512)
    private String apiBaseUrl;

    /** 本 installation 绑定到的 RepoSage 内部项目(已知时)。 */
    @Column(name = "project_id")
    private Long projectId;

    /** 本 installation 绑定到的 RepoSage 内部仓库(已知时)。 */
    @Column(name = "repository_id")
    private Long repositoryId;

    @Column(name = "encrypted_webhook_secret", columnDefinition = "TEXT")
    private String encryptedWebhookSecret;

    /** 用于回调的 GitHub App 私钥或 GitLab 访问令牌,加密存储。 */
    @Column(name = "encrypted_credential", columnDefinition = "TEXT")
    private String encryptedCredential;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

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

    public String getExternalInstallationId() {
        return externalInstallationId;
    }

    public void setExternalInstallationId(String externalInstallationId) {
        this.externalInstallationId = externalInstallationId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(Long repositoryId) {
        this.repositoryId = repositoryId;
    }

    public String getEncryptedWebhookSecret() {
        return encryptedWebhookSecret;
    }

    public void setEncryptedWebhookSecret(String encryptedWebhookSecret) {
        this.encryptedWebhookSecret = encryptedWebhookSecret;
    }

    public String getEncryptedCredential() {
        return encryptedCredential;
    }

    public void setEncryptedCredential(String encryptedCredential) {
        this.encryptedCredential = encryptedCredential;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
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
