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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 一个项目唯一的活动源码仓库，连同其静态存储的凭据。
 *
 * <p>{@code provider + instance_identity + external_id} 是它的稳定身份（D010）。
 * 它是**全局唯一**而非项目内唯一（design.md 3.6），因为 webhook 投递是靠载荷
 * 内部的仓库身份来路由的：如果两个项目能注册同一个仓库，一次投递就会有两个
 * 目标、两份密钥，任何签名校验都将无从定义。
 */
@Entity
@Table(name = "scm_repository")
public class ScmRepository {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private ScmProvider provider;

    @Column(name = "instance_identity", nullable = false, length = 255)
    private String instanceIdentity;

    @Column(name = "external_id", nullable = false, length = 128)
    private String externalId;

    @Column(name = "api_base", nullable = false, length = 512)
    private String apiBase;

    @Column(name = "encrypted_token", nullable = false)
    private String encryptedToken;

    @Column(name = "encrypted_secret", nullable = false)
    private String encryptedSecret;

    @Column(name = "identity_approval_required", nullable = false)
    private boolean identityApprovalRequired;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ScmRepository() {
    }

    ScmRepository(Long projectId, ScmProvider provider, String instanceIdentity, String externalId,
            String apiBase, String encryptedToken, String encryptedSecret) {
        this.projectId = projectId;
        this.provider = provider;
        this.instanceIdentity = instanceIdentity;
        this.externalId = externalId;
        this.apiBase = apiBase;
        this.encryptedToken = encryptedToken;
        this.encryptedSecret = encryptedSecret;
        this.identityApprovalRequired = false;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public ScmProvider getProvider() {
        return provider;
    }

    public String getInstanceIdentity() {
        return instanceIdentity;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getApiBase() {
        return apiBase;
    }

    /** 密文，且只会交给 {@link ScmSecretCipher}。绝不会被序列化进任何响应。 */
    public String getEncryptedToken() {
        return encryptedToken;
    }

    /** 密文，且只会交给 {@link ScmSecretCipher}。绝不会被序列化进任何响应。 */
    public String getEncryptedSecret() {
        return encryptedSecret;
    }

    public boolean isIdentityApprovalRequired() {
        return identityApprovalRequired;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 整个稳定身份要一次性整体迁移，因为它本就是一个事实。它究竟允不允许迁移，
     * 取决于另一张表里有没有行——这是任何即时约束都无法表达的，
     * 因此由 {@link ScmRepositoryService} 在行锁下判定（design.md 3.7）。
     */
    void reidentify(ScmProvider provider, String instanceIdentity, String externalId) {
        this.provider = provider;
        this.instanceIdentity = instanceIdentity;
        this.externalId = externalId;
    }

    void changeApiBase(String apiBase) {
        this.apiBase = apiBase;
    }

    void rotateCredentials(String encryptedToken, String encryptedSecret) {
        this.encryptedToken = encryptedToken;
        this.encryptedSecret = encryptedSecret;
    }

    void changeIdentityApprovalRequired(boolean required) {
        this.identityApprovalRequired = required;
    }
}
