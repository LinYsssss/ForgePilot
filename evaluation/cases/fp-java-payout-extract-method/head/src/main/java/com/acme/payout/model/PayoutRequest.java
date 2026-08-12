package com.acme.payout.model;

import java.time.Instant;

/**
 * 打款请求。
 *
 * <p>金额一律以「分」为单位存储，使用 long。
 */
public class PayoutRequest {

    private Long id;
    private final Long tenantId;
    private final Long merchantId;
    private final String requestKey;
    private final long grossAmountFen;
    private final long commissionFen;
    private final long netAmountFen;
    private final String currency;
    private String status;
    private final Instant createdAt;

    public PayoutRequest(Long tenantId, Long merchantId, String requestKey,
                         long grossAmountFen, long commissionFen, String currency) {
        this.tenantId = tenantId;
        this.merchantId = merchantId;
        this.requestKey = requestKey;
        this.grossAmountFen = grossAmountFen;
        this.commissionFen = commissionFen;
        this.netAmountFen = grossAmountFen - commissionFen;
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

    public String getRequestKey() {
        return requestKey;
    }

    public long getGrossAmountFen() {
        return grossAmountFen;
    }

    public long getCommissionFen() {
        return commissionFen;
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

    public void markProcessing() {
        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("只有 PENDING 状态可以发起打款，当前为 " + status);
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
