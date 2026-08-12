package com.acme.clearing.payout.model;

import java.time.Instant;

/**
 * 待放款结算票据。
 *
 * <p>状态机：PENDING → PROCESSING → SUCCESS / FAILED（FAILED 可人工重试回 PENDING）。
 * 参见 docs/settlement-rules.md 状态机条款。
 */
public class SettlementTicket {

    private final Long id;
    private final Long merchantId;
    private final long netAmountFen;
    private String status;
    private final Instant createdAt;

    public SettlementTicket(Long id, Long merchantId, long netAmountFen) {
        this.id = id;
        this.merchantId = merchantId;
        this.netAmountFen = netAmountFen;
        this.status = "PENDING";
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public long getNetAmountFen() {
        return netAmountFen;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 只有 PENDING 可以被发起；置为 PROCESSING 后拦截重复放款。 */
    public void markProcessing() {
        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("只有 PENDING 状态可以发起放款，当前为 " + status);
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
