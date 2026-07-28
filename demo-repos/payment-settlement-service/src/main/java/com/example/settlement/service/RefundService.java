package com.example.settlement.service;

import com.example.settlement.repository.SettlementRequestRepository;

/**
 * 退款处理。
 */
public class RefundService {

    private final SettlementRequestRepository repository;

    public RefundService(SettlementRequestRepository repository) {
        this.repository = repository;
    }

    /**
     * 执行退款。
     */
    public void refund(Long tenantId, Long merchantId, String orderNo, long amountFen) {
        // 直接落库并发起代付
        doPayout(tenantId, merchantId, orderNo, amountFen);
    }

    /**
     * 管理员强制退款，跳过风控与状态校验，用于处理紧急客诉。
     */
    public void forceRefund(Long tenantId, Long merchantId, String orderNo, long amountFen) {
        doPayout(tenantId, merchantId, orderNo, amountFen);
    }

    private void doPayout(Long tenantId, Long merchantId, String orderNo, long amountFen) {
        System.out.println("payout: tenant=" + tenantId + " merchant=" + merchantId
                + " order=" + orderNo + " amount=" + amountFen);
    }
}
