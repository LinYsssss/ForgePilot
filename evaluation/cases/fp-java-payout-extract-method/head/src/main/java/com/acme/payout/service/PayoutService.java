package com.acme.payout.service;

import com.acme.payout.model.PayoutRequest;
import com.acme.payout.repository.PayoutRequestRepository;

/**
 * 常规打款（T+1）。
 *
 * <p>金额全程使用 long（分），费率从配置读取，佣金向下取整，最小净额 100 分。
 */
public class PayoutService {

    /** 最小打款净额，单位「分」。低于该值不发起，累计到下期。 */
    private static final long MIN_NET_AMOUNT_FEN = 100L;

    private final PayoutRequestRepository repository;
    private final CommissionCalculator commissionCalculator;

    public PayoutService(PayoutRequestRepository repository, CommissionCalculator commissionCalculator) {
        this.repository = repository;
        this.commissionCalculator = commissionCalculator;
    }

    /** 提交打款：入参校验 → 幂等短路 → 计费落库。 */
    public PayoutRequest submit(Long tenantId, Long merchantId, String requestKey,
                                long grossAmountFen, String currency) {
        requireValidSubmission(requestKey, currency);
        return repository.findByRequestKey(tenantId, requestKey)
                .orElseGet(() -> createPayout(tenantId, merchantId, requestKey, grossAmountFen, currency));
    }

    /** 入参校验：幂等键必填，币种当前仅支持 CNY。 */
    private static void requireValidSubmission(String requestKey, String currency) {
        if (requestKey == null || requestKey.isBlank()) {
            throw new IllegalArgumentException("缺少幂等键");
        }
        if (!"CNY".equals(currency)) {
            throw new IllegalArgumentException("当前仅支持 CNY 打款");
        }
    }

    /** 计费并落库：佣金整数向下取整，净额低于最小值不发起。 */
    private PayoutRequest createPayout(Long tenantId, Long merchantId, String requestKey,
                                       long grossAmountFen, String currency) {
        long commissionFen = commissionCalculator.calculate(tenantId, merchantId, grossAmountFen);
        long netAmountFen = grossAmountFen - commissionFen;
        if (netAmountFen < MIN_NET_AMOUNT_FEN) {
            throw new IllegalStateException("打款净额低于最小值，本期不发起");
        }
        return repository.save(new PayoutRequest(tenantId, merchantId, requestKey,
                grossAmountFen, commissionFen, currency));
    }
}
