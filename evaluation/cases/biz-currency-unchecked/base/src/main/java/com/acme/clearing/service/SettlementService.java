package com.acme.clearing.service;

import com.acme.clearing.model.SettlementOrder;

/**
 * 商户结算提交。
 *
 * <p>结算规则参见 docs/settlement-rules.md：当前仅支持人民币（CNY）结算。
 */
public class SettlementService {

    /** 最小结算净额（分），低于该值不发起，累计到下期。 */
    private static final long MIN_NET_AMOUNT_FEN = 100L;

    /** 平台当前唯一支持的结算币种。 */
    private static final String SETTLE_CURRENCY = "CNY";

    private final FeeCalculator feeCalculator;

    public SettlementService(FeeCalculator feeCalculator) {
        this.feeCalculator = feeCalculator;
    }

    public SettlementOrder submit(Long tenantId, Long merchantId, String idempotencyKey,
                                  long grossAmountFen) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("缺少幂等键");
        }
        long feeFen = feeCalculator.calculate(tenantId, merchantId, grossAmountFen);
        long netFen = grossAmountFen - feeFen;
        if (netFen < MIN_NET_AMOUNT_FEN) {
            throw new IllegalStateException("结算净额低于最小值，本期不发起");
        }
        return new SettlementOrder(tenantId, merchantId, idempotencyKey,
                grossAmountFen, feeFen, SETTLE_CURRENCY);
    }
}
