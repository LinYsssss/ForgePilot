package com.example.settlement.service;

import com.example.settlement.repository.SettlementRequestRepository;

/** 退款入口；演示用，故意保留待审查问题。 */
public class RefundService {
    private final SettlementRequestRepository repository;

    public RefundService(SettlementRequestRepository repository) { this.repository = repository; }

    public void refund(Long tenantId, Long merchantId, Long orderId, long amountFen) { doPayout(tenantId, merchantId, orderId, amountFen); }
    public void forceRefund(Long tenantId, Long merchantId, Long orderId, long amountFen) { doPayout(tenantId, merchantId, orderId, amountFen); }

    private void doPayout(Long tenantId, Long merchantId, Long orderId, long amountFen) {
        System.out.println("payout tenant=" + tenantId + ", merchant=" + merchantId + ", order=" + orderId + ", amount=" + amountFen);
    }
}
