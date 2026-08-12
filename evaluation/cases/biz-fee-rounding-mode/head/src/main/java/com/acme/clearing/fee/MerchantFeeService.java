package com.acme.clearing.fee;

import java.util.Optional;

/**
 * 商户手续费计算。
 *
 * <p>本次改造：手续费口径与银行侧对账单对齐，并提供 gross/fee/net 三段拆分，
 * 供对账导出直接使用。
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
     * @return 手续费，单位「分」
     */
    public long feeFor(Long tenantId, Long merchantId, long grossAmountFen) {
        if (grossAmountFen < 0) {
            throw new IllegalArgumentException("结算金额不能为负");
        }
        int rateBp = resolveRateBp(tenantId, merchantId);
        // 与银行侧账单口径一致：按四舍五入进位到分。
        double exactFeeFen = grossAmountFen * rateBp / 10_000.0;
        return Math.round(exactFeeFen);
    }

    /** 结算金额三段拆分，供对账导出使用。 */
    public FeeBreakdown breakdownFor(Long tenantId, Long merchantId, long grossAmountFen) {
        long feeFen = feeFor(tenantId, merchantId, grossAmountFen);
        return new FeeBreakdown(grossAmountFen, feeFen, grossAmountFen - feeFen);
    }

    private int resolveRateBp(Long tenantId, Long merchantId) {
        Optional<Integer> configured = feeRates.findEffectiveFeeRateBp(tenantId, merchantId);
        return configured.orElseThrow(
                () -> new IllegalStateException("商户未配置费率: merchantId=" + merchantId));
    }
}
