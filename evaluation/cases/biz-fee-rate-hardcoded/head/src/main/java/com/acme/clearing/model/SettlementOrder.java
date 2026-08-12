package com.acme.clearing.model;

import java.time.Instant;

/**
 * 结算单。
 *
 * <p>金额一律以「分」为单位，使用 long 存储与计算，参见 docs/settlement-rules.md。
 */
public class SettlementOrder {

    private Long id;
    private final Long tenantId;
    private final Long merchantId;
    private final String idempotencyKey;
    private final long grossAmount;
    private final long feeAmount;
    private final long netAmount;
    private final String currency;
    private String status;
    private final Instant createdAt;

    public SettlementOrder(Long tenantId, Long merchantId, String idempotencyKey,
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
}
