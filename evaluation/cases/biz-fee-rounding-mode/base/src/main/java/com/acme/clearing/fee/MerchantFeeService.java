package com.acme.clearing.fee;

import java.util.Optional;

/**
 * 商户手续费计算。
 *
 * <p>金额与精度规则见 docs/settlement-rules.md：金额一律为「分」（long），
 * 手续费向下取整（截断），差额由平台承担。
 */
public class MerchantFeeService {

    private final FeeRateRepository feeRates;

    public MerchantFeeService(FeeRateRepository feeRates) {
        this.feeRates = feeRates;
    }

    /**
     * 计算单笔结算手续费。
     *
     * @param grossAmountFen 结算总额，单位「分」
     * @return 手续费，单位「分」，向下取整
     */
    public long feeFor(Long tenantId, Long merchantId, long grossAmountFen) {
        if (grossAmountFen < 0) {
            throw new IllegalArgumentException("结算金额不能为负");
        }
        int rateBp = resolveRateBp(tenantId, merchantId);
        // 基点为万分之一；整数除法天然向下取整，不引入浮点。
        return grossAmountFen * rateBp / 10_000L;
    }

    private int resolveRateBp(Long tenantId, Long merchantId) {
        Optional<Integer> configured = feeRates.findEffectiveFeeRateBp(tenantId, merchantId);
        return configured.orElseThrow(
                () -> new IllegalStateException("商户未配置费率: merchantId=" + merchantId));
    }
}
