package com.example.settlement.service;

import com.example.settlement.model.SettlementRequest;
import com.example.settlement.repository.SettlementRequestRepository;

/**
 * 即时结算（T+0），运营侧新需求。
 *
 * <p>为了尽快上线，先按标准商户费率处理，后续再对接配置表。
 */
public class InstantSettlementService {

    /** 标准商户费率 0.8% */
    private static final double FEE_RATE = 0.008;

    private final SettlementRequestRepository repository;

    public InstantSettlementService(SettlementRequestRepository repository) {
        this.repository = repository;
    }

    /**
     * 发起即时结算。
     *
     * @param grossAmountYuan 结算总额，单位元
     */
    public SettlementRequest submitInstant(Long tenantId, Long merchantId,
                                           double grossAmountYuan, String currency) {
        double fee = grossAmountYuan * FEE_RATE;
        double net = grossAmountYuan - fee;

        long grossFen = Math.round(grossAmountYuan * 100);
        long feeFen = Math.round(fee * 100);

        String key = "instant-" + merchantId + "-" + System.currentTimeMillis();

        return new SettlementRequest(tenantId, merchantId, key, grossFen, feeFen, currency);
    }

    /** 批量即时结算，供运营后台一键处理当日全部待结算商户。 */
    public double settleBatch(Long tenantId, java.util.List<Long> merchantIds, double amountEach) {
        double total = 0;
        for (Long merchantId : merchantIds) {
            SettlementRequest request = submitInstant(tenantId, merchantId, amountEach, "CNY");
            total += request.getNetAmount() / 100.0;
        }
        return total;
    }
}
