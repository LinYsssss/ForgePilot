package com.acme.payout.service;

import com.acme.payout.model.PayoutRequest;
import com.acme.payout.repository.PayoutRequestRepository;
import java.util.Optional;

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

    public PayoutRequest submit(Long tenantId, Long merchantId, String requestKey,
                                long grossAmountFen, String currency) {
        if (requestKey == null || requestKey.isBlank()) {
            throw new IllegalArgumentException("缺少幂等键");
        }
        if (!"CNY".equals(currency)) {
            throw new IllegalArgumentException("当前仅支持 CNY 打款");
        }

        Optional<PayoutRequest> existing = repository.findByRequestKey(tenantId, requestKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        long commissionFen = commissionCalculator.calculate(tenantId, merchantId, grossAmountFen);
        long netFen = grossAmountFen - commissionFen;
        if (netFen < MIN_NET_AMOUNT_FEN) {
            throw new IllegalStateException("打款净额低于最小值，本期不发起");
        }

        return repository.save(new PayoutRequest(tenantId, merchantId, requestKey,
                grossAmountFen, commissionFen, currency));
    }
}
