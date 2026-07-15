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
 * A provider binding that lets RepoSage receive webhooks for, and call back to, a single repository
 * host.
 *
 * <p>For GitHub this is a GitHub App installation (App id + encrypted private key + webhook secret);
 * for GitLab v1 it is an encrypted project access token plus webhook secret. Both credential columns
 * are stored encrypted (see {@code CryptoService}) and must never be returned by read APIs. The
 * installation is the <em>only</em> source of secrets and API host — these are resolved from the
 * verified identity in a delivery, never trusted from payload-supplied fields. Rotation is an update
 * of the encrypted columns; {@code updatedAt} records when it last happened.
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

    /** GitHub App installation id, or GitLab project id — the provider's own identifier. */
    @Column(name = "external_installation_id", nullable = false, length = 255)
    private String externalInstallationId;

    @Column(name = "display_name", length = 255)
    private String displayName;

    /** GitHub App id (null for GitLab). */
    @Column(name = "app_id", length = 128)
    private String appId;

    /** Allowed API host for callbacks; resolved here, never from the webhook payload. */
    @Column(name = "api_base_url", length = 512)
    private String apiBaseUrl;

    /** Internal RepoSage project this installation is bound to, when known. */
    @Column(name = "project_id")
    private Long projectId;

    /** Internal RepoSage repository this installation is bound to, when known. */
    @Column(name = "repository_id")
    private Long repositoryId;

    @Column(name = "encrypted_webhook_secret", columnDefinition = "TEXT")
    private String encryptedWebhookSecret;

    /** Encrypted GitHub App private key or GitLab access token used for callbacks. */
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
