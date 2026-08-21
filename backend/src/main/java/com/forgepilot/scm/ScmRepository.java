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
 * A project's one active source control repository, with its credentials at rest.
 *
 * <p>{@code provider + instance_identity + external_id} is the stable identity
 * (D010). It is unique globally rather than per project (design.md 3.6) because a
 * webhook delivery is routed by the repository identity inside its payload: if two
 * projects could register one repository, a single delivery would have two targets
 * and two secrets and no signature check would be well defined.
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

    /** Ciphertext, and only ever handed to {@link ScmSecretCipher}. Never serialized into a response. */
    public String getEncryptedToken() {
        return encryptedToken;
    }

    /** Ciphertext, and only ever handed to {@link ScmSecretCipher}. Never serialized into a response. */
    public String getEncryptedSecret() {
        return encryptedSecret;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * The whole stable identity moves at once, because it is one fact. Whether it
     * is allowed to move at all depends on another table having no rows, which no
     * immediate constraint can express, so {@link ScmRepositoryService} decides it
     * under a row lock (design.md 3.7).
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
}
