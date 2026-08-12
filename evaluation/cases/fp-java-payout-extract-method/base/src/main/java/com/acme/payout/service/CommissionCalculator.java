package com.acme.payout.service;

import java.util.Optional;

/**
 * 佣金计算。
 *
 * <p>费率从商户配置读取，不在代码中硬编码；结果向下取整，差额由平台承担。
 */
public class CommissionCalculator {

    private final MerchantRateRepository rates;

    public CommissionCalculator(MerchantRateRepository rates) {
        this.rates = rates;
    }

    /**
     * @param grossAmountFen 打款总额，单位「分」
     * @return 佣金，单位「分」，向下取整
     */
    public long calculate(Long tenantId, Long merchantId, long grossAmountFen) {
        if (grossAmountFen < 0) {
            throw new IllegalArgumentException("打款金额不能为负");
        }
        int rateBp = resolveRateBp(tenantId, merchantId);
        // 基点为万分之一；整数运算天然向下取整，不引入浮点。
        return grossAmountFen * rateBp / 10_000L;
    }

    private int resolveRateBp(Long tenantId, Long merchantId) {
        Optional<Integer> configured = rates.findEffectiveRateBp(tenantId, merchantId);
        return configured.orElseThrow(
                () -> new IllegalStateException("商户未配置费率: merchantId=" + merchantId));
    }
}
