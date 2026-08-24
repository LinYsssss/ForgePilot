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

@Entity
@Table(name = "scm_identity")
class ScmIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 32)
    private ScmProvider provider;

    @Column(name = "instance_identity", length = 255)
    private String instanceIdentity;

    @Column(name = "external_user_id", nullable = false, length = 128)
    private String externalUserId;

    @Column(name = "external_username", nullable = false, length = 128)
    private String externalUsername;

    @Column(name = "label", nullable = false, length = 120)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_type", nullable = false, length = 16)
    private ScmIdentityUsage usageType;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 32)
    private VerificationStatus verificationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_method", nullable = false, length = 32)
    private VerificationMethod verificationMethod;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ScmIdentity() {
    }

    ScmIdentity(long userId, VerifiedScmUser verified, String label, ScmIdentityUsage usageType, Instant now) {
        this.userId = userId;
        this.provider = verified.provider();
        this.instanceIdentity = verified.instanceIdentity();
        this.externalUserId = verified.externalUserId();
        this.externalUsername = verified.externalUsername();
        this.label = label.trim();
        this.usageType = usageType;
        this.verificationStatus = VerificationStatus.VERIFIED;
        this.verificationMethod = VerificationMethod.ONE_TIME_TOKEN;
        this.verifiedAt = now;
        this.lastSyncedAt = now;
    }

    Long getId() { return id; }
    Long getUserId() { return userId; }
    ScmProvider getProvider() { return provider; }
    String getInstanceIdentity() { return instanceIdentity; }
    String getExternalUserId() { return externalUserId; }
    String getExternalUsername() { return externalUsername; }
    String getLabel() { return label; }
    ScmIdentityUsage getUsageType() { return usageType; }
    VerificationStatus getVerificationStatus() { return verificationStatus; }
    Instant getVerifiedAt() { return verifiedAt; }
    Instant getLastSyncedAt() { return lastSyncedAt; }

    boolean isVerified() { return verificationStatus == VerificationStatus.VERIFIED; }

    void rename(String newLabel, ScmIdentityUsage newUsageType) {
        this.label = newLabel.trim();
        this.usageType = newUsageType;
    }

    void refresh(VerifiedScmUser verified, Instant now) {
        this.externalUsername = verified.externalUsername();
        this.verificationStatus = VerificationStatus.VERIFIED;
        this.verifiedAt = now;
        this.lastSyncedAt = now;
    }

    void revoke() {
        this.verificationStatus = VerificationStatus.REVOKED;
    }

    enum VerificationStatus { VERIFIED, LEGACY_UNCONFIRMED, REVOKED }
    enum VerificationMethod { ONE_TIME_TOKEN, LEGACY_ADMIN_ASSERTED }
}
