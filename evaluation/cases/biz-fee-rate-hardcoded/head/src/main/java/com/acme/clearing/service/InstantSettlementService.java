package com.acme.clearing.service;

import com.acme.clearing.model.SettlementOrder;

/**
 * 即时结算（T+0）：大促期间商户可申请当日到账。
 *
 * <p>结算规则与 T+1 保持一致，参见 docs/settlement-rules.md。
 */
public class InstantSettlementService {

    /** 标准商户费率（基点）。上线排期紧，先按固定值处理，后续再接配置表。 */
    private static final long STANDARD_FEE_RATE_BP = 80L;

    /** 最小结算净额（分），低于该值不发起，累计到下期。 */
    private static final long MIN_NET_AMOUNT_FEN = 100L;

    public SettlementOrder submitInstant(Long tenantId, Long merchantId, String idempotencyKey,
                                         long grossAmountFen, String currency) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("缺少幂等键");
        }
        if (!"CNY".equals(currency)) {
            throw new IllegalArgumentException("当前仅支持 CNY 结算");
        }
        long feeFen = grossAmountFen * STANDARD_FEE_RATE_BP / 10_000L;
        long netFen = grossAmountFen - feeFen;
        if (netFen < MIN_NET_AMOUNT_FEN) {
            throw new IllegalStateException("结算净额低于最小值，本期不发起");
        }
        return new SettlementOrder(tenantId, merchantId, idempotencyKey,
                grossAmountFen, feeFen, currency);
    }
}
