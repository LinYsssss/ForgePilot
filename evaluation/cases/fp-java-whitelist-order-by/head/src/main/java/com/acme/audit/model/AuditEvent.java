package com.acme.audit.model;

import java.time.Instant;

/** 审计事件（只读投影）。 */
public class AuditEvent {

    private final long id;
    private final long tenantId;
    private final String action;
    private final String operator;
    private final String detail;
    private final Instant createdAt;

    public AuditEvent(long id, long tenantId, String action, String operator,
                      String detail, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.action = action;
        this.operator = operator;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public long getId() {
        return id;
    }

    public long getTenantId() {
        return tenantId;
    }

    public String getAction() {
        return action;
    }

    public String getOperator() {
        return operator;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
