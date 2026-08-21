package com.acme.clearing.controller;

import com.acme.clearing.model.SettlementOrder;
import com.acme.clearing.service.SettlementService;

/**
 * 商户结算 HTTP 入口（框架适配层省略，演示服务用）。
 */
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /**
     * 商户发起结算。
     *
     * @param grossAmountFen 结算总额，单位「分」
     * @param currency 结算币种，透传给结算服务
     */
    public String submit(Long tenantId, Long merchantId, String idempotencyKey,
                         long grossAmountFen, String currency) {
        SettlementOrder order = settlementService.submit(tenantId, merchantId, idempotencyKey,
                grossAmountFen, currency);
        return "accepted:" + order.getIdempotencyKey();
    }
}
