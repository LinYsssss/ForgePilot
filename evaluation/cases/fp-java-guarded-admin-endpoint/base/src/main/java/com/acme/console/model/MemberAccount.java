package com.acme.console.model;

import java.time.Instant;

/** 工作台成员账号。 */
public class MemberAccount {

    private final Long id;
    private final Long tenantId;
    private final String email;
    private String displayName;
    private String status;
    private final Instant createdAt;

    public MemberAccount(Long id, Long tenantId, String email, String displayName,
                         String status, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.email = email;
        this.displayName = displayName;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public void rename(String newDisplayName) {
        this.displayName = newDisplayName;
    }

    public void deactivate() {
        this.status = "DISABLED";
    }
}
