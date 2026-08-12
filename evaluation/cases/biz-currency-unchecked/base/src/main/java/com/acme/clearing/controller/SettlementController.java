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
     */
    public String submit(Long tenantId, Long merchantId, String idempotencyKey, long grossAmountFen) {
        SettlementOrder order = settlementService.submit(tenantId, merchantId, idempotencyKey, grossAmountFen);
        return "accepted:" + order.getIdempotencyKey();
    }
}
