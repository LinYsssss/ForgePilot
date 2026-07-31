package com.example.settlement.service;

import com.example.settlement.model.SettlementRequest;
import com.example.settlement.repository.SettlementRequestRepository;
import java.util.Optional;

/**
 * 常规结算（T+1）。
 *
 * <p>金额全程使用 long（分），费率从配置读取，手续费向下取整，最小净额 100 分。
 * 参见 docs/settlement-rules.md。
 */
public class SettlementService {

    /** 最小结算净额，单位「分」。低于该值不发起，累计到下期。见 settlement-rules.md 第 4 节。 */
    private static final long MIN_NET_AMOUNT_FEN = 100L;

    private final SettlementRequestRepository repository;
    private final FeeCalculator feeCalculator;

    public SettlementService(SettlementRequestRepository repository, FeeCalculator feeCalculator) {
        this.repository = repository;
        this.feeCalculator = feeCalculator;
    }

    public SettlementRequest submit(Long tenantId, Long merchantId, String idempotencyKey,
                                    long grossAmountFen, String currency) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("缺少幂等键");
        }
        if (!"CNY".equals(currency)) {
            throw new IllegalArgumentException("当前仅支持 CNY 结算");
        }

        Optional<SettlementRequest> existing = repository.findByIdempotencyKey(tenantId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        long feeFen = feeCalculator.calculate(tenantId, merchantId, grossAmountFen);
        long netFen = grossAmountFen - feeFen;
        if (netFen < MIN_NET_AMOUNT_FEN) {
            throw new IllegalStateException("结算净额低于最小值，本期不发起");
        }

        return new SettlementRequest(tenantId, merchantId, idempotencyKey, grossAmountFen, feeFen, currency);
    }
}
