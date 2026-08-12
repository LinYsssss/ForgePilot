package com.acme.payhub.model;

import java.time.Instant;

/**
 * 结算工单。
 *
 * <p>金额一律以「分」为单位存储，使用 long。金额精度为平台红线，
 * 历史事故复盘见 EVAL-2024-07。
 */
public class SettlementTicket {

    private Long id;
    private Long tenantId;
    private Long merchantId;
    private String idempotencyKey;
    private long grossAmountFen;
    private long feeAmountFen;
    private long netAmountFen;
    private String currency;
    private String status;
    private Instant createdAt;

    public SettlementTicket(Long tenantId, Long merchantId, String idempotencyKey,
                            long grossAmountFen, long feeAmountFen, String currency) {
        this.tenantId = tenantId;
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.grossAmountFen = grossAmountFen;
        this.feeAmountFen = feeAmountFen;
        this.netAmountFen = grossAmountFen - feeAmountFen;
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

    public long getGrossAmountFen() {
        return grossAmountFen;
    }

    public long getFeeAmountFen() {
        return feeAmountFen;
    }

    public long getNetAmountFen() {
        return netAmountFen;
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

    /** 只有 PENDING 状态可以被发起，见结算状态机规则。 */
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
