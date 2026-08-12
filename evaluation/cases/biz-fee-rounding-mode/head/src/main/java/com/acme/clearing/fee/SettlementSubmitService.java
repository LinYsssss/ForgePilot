package com.acme.clearing.fee;

/**
 * 结算提交入口：拿到手续费后校验最小净额并落单。
 */
public class SettlementSubmitService {

    /** 最小结算净额（分），低于该值不发起，累计到下期。 */
    private static final long MIN_NET_AMOUNT_FEN = 100L;

    private final MerchantFeeService merchantFeeService;

    public SettlementSubmitService(MerchantFeeService merchantFeeService) {
        this.merchantFeeService = merchantFeeService;
    }

    /**
     * @return 结算净额（分）
     */
    public long submit(Long tenantId, Long merchantId, String idempotencyKey, long grossAmountFen) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("缺少幂等键");
        }
        long feeFen = merchantFeeService.feeFor(tenantId, merchantId, grossAmountFen);
        long netFen = grossAmountFen - feeFen;
        if (netFen < MIN_NET_AMOUNT_FEN) {
            throw new IllegalStateException("结算净额低于最小值，本期不发起");
        }
        return netFen;
    }
}
