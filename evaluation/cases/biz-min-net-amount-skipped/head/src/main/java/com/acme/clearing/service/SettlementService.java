package com.acme.clearing.service;

import com.acme.clearing.model.SettlementOrder;

/**
 * 常规结算（T+1）。
 *
 * <p>费率经 FeeCalculator 从配置表读取，手续费向下取整，最小净额 100 分。
 * 参见 docs/settlement-rules.md。
 */
public class SettlementService {

    /** 最小结算净额，单位「分」。低于该值不发起，累计到下期。 */
    private static final long MIN_NET_AMOUNT_FEN = 100L;

    private final FeeCalculator feeCalculator;

    public SettlementService(FeeCalculator feeCalculator) {
        this.feeCalculator = feeCalculator;
    }

    public SettlementOrder submit(Long tenantId, Long merchantId, String idempotencyKey,
                                  long grossAmountFen, String currency) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("缺少幂等键");
        }
        if (!"CNY".equals(currency)) {
            throw new IllegalArgumentException("当前仅支持 CNY 结算");
        }
        long feeFen = feeCalculator.calculate(tenantId, merchantId, grossAmountFen);
        long netFen = grossAmountFen - feeFen;
        if (netFen < MIN_NET_AMOUNT_FEN) {
            throw new IllegalStateException("结算净额低于最小值，本期不发起");
        }
        return new SettlementOrder(tenantId, merchantId, idempotencyKey, grossAmountFen, feeFen, currency);
    }
}
