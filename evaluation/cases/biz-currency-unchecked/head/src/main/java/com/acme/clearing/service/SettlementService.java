package com.acme.clearing.service;

import com.acme.clearing.model.SettlementOrder;

/**
 * 商户结算提交。
 *
 * <p>本次改造：结算入口开放 currency 参数，为跨境结算接入做准备。
 */
public class SettlementService {

    /** 最小结算净额（分），低于该值不发起，累计到下期。 */
    private static final long MIN_NET_AMOUNT_FEN = 100L;

    private final FeeCalculator feeCalculator;

    public SettlementService(FeeCalculator feeCalculator) {
        this.feeCalculator = feeCalculator;
    }

    /**
     * 商户发起结算。
     *
     * @param currency 结算币种，例如 CNY、USD、HKD
     */
    public SettlementOrder submit(Long tenantId, Long merchantId, String idempotencyKey,
                                  long grossAmountFen, String currency) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("缺少幂等键");
        }
        long feeFen = feeCalculator.calculate(tenantId, merchantId, grossAmountFen);
        long netFen = grossAmountFen - feeFen;
        if (netFen < MIN_NET_AMOUNT_FEN) {
            throw new IllegalStateException("结算净额低于最小值，本期不发起");
        }
        return new SettlementOrder(tenantId, merchantId, idempotencyKey,
                grossAmountFen, feeFen, currency);
    }
}
