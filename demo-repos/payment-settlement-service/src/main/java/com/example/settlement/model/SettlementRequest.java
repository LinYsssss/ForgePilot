package com.example.settlement.model;

import java.time.Instant;

/**
 * 结算请求。
 *
 * <p>金额一律以「分」为单位存储，使用 long。参见 docs/db-schema.md。
 */
public class SettlementRequest {

    private Long id;
    private Long tenantId;
    private Long merchantId;
    private String idempotencyKey;
    private long grossAmount;
    private long feeAmount;
    private long netAmount;
    private String currency;
    private String status;
    private Instant createdAt;

    public SettlementRequest(Long tenantId, Long merchantId, String idempotencyKey,
                             long grossAmount, long feeAmount, String currency) {
        this.tenantId = tenantId;
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.grossAmount = grossAmount;
        this.feeAmount = feeAmount;
        this.netAmount = grossAmount - feeAmount;
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

    public long getGrossAmount() {
        return grossAmount;
    }

    public long getFeeAmount() {
        return feeAmount;
    }

    public long getNetAmount() {
        return netAmount;
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
            throw new IllegalStateException("只有 PENDING 状态可以发起结算，当前为 " + status);
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
