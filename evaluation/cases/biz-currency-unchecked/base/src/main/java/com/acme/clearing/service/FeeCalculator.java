package com.acme.clearing.service;

import java.util.Optional;

/**
 * 手续费计算。费率从 fee_rate_config 表读取，结果向下取整。
 */
public class FeeCalculator {

    private final FeeRateConfigRepository feeConfigs;

    public FeeCalculator(FeeRateConfigRepository feeConfigs) {
        this.feeConfigs = feeConfigs;
    }

    public long calculate(Long tenantId, Long merchantId, long grossAmountFen) {
        if (grossAmountFen < 0) {
            throw new IllegalArgumentException("结算金额不能为负");
        }
        Optional<Integer> rateBp = feeConfigs.findEffectiveFeeRateBp(tenantId, merchantId);
        int bp = rateBp.orElseThrow(
                () -> new IllegalStateException("商户未配置费率: merchantId=" + merchantId));
        // 整数运算天然向下取整，不引入浮点。
        return grossAmountFen * bp / 10_000L;
    }
}
