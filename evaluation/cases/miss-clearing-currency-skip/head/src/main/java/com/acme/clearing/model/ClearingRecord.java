package com.acme.clearing.model;

import java.time.Instant;

/**
 * 清分记录。金额单位「分」（long），状态机：PENDING → PROCESSING → SUCCESS/FAILED。
 */
public class ClearingRecord {

    private Long id;
    private final Long tenantId;
    private final Long merchantId;
    private final String idempotencyKey;
    private final long grossFen;
    private final long feeFen;
    private final long netFen;
    private final String currency;
    private String status;
    private final Instant createdAt;

    public ClearingRecord(Long tenantId, Long merchantId, String idempotencyKey,
                          long grossFen, long feeFen, String currency) {
        this.tenantId = tenantId;
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.grossFen = grossFen;
        this.feeFen = feeFen;
        this.netFen = grossFen - feeFen;
        this.currency = currency;
        this.status = "PENDING";
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public long getGrossFen() {
        return grossFen;
    }

    public long getFeeFen() {
        return feeFen;
    }

    public long getNetFen() {
        return netFen;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void markProcessing() {
        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("只有 PENDING 可发起清分，当前为 " + status);
        }
        this.status = "PROCESSING";
    }

    public void markSuccess() {
        this.status = "SUCCESS";
    }

    public void markFailed() {
        this.status = "FAILED";
    }
}
