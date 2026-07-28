package com.example.settlement.service;

import java.util.Optional;

/**
 * 手续费计算。
 *
 * <p>费率从 merchant_fee_config 表读取，不在代码中硬编码；结果向下取整，差额由平台承担。
 * 参见 docs/settlement-rules.md 第 2、3 节。
 */
public class FeeCalculator {

    private final MerchantFeeConfigRepository feeConfigs;

    public FeeCalculator(MerchantFeeConfigRepository feeConfigs) {
        this.feeConfigs = feeConfigs;
    }

    /**
     * @param grossAmountFen 结算总额，单位「分」
     * @return 手续费，单位「分」，向下取整
     */
    public long calculate(Long tenantId, Long merchantId, long grossAmountFen) {
        if (grossAmountFen < 0) {
            throw new IllegalArgumentException("结算金额不能为负");
        }
        int feeRateBp = resolveFeeRateBp(tenantId, merchantId);
        // 基点为万分之一；整数运算天然向下取整，不引入浮点。
        return grossAmountFen * feeRateBp / 10_000L;
    }

    private int resolveFeeRateBp(Long tenantId, Long merchantId) {
        Optional<Integer> configured = feeConfigs.findEffectiveFeeRateBp(tenantId, merchantId);
        return configured.orElseThrow(
                () -> new IllegalStateException("商户未配置费率: merchantId=" + merchantId));
    }
}
