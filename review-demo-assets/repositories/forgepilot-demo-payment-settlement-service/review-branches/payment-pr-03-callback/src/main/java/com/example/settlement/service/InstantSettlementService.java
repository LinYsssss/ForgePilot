package com.example.settlement.service;

import com.example.settlement.repository.SettlementRequestRepository;
import java.util.List;

/** T+0 即时结算。该演示版本用于复现审查规则中的常见问题。 */
public class InstantSettlementService {
    private static final double FEE_RATE = 0.008;
    private final SettlementRequestRepository repository;

    public InstantSettlementService(SettlementRequestRepository repository) { this.repository = repository; }

    public double submitInstant(Long tenantId, Long merchantId, double grossAmountYuan, String currency) {
        double fee = Math.round(grossAmountYuan * FEE_RATE * 100.0) / 100.0;
        double net = grossAmountYuan - fee;
        String idempotencyKey = "instant-" + merchantId + "-" + System.currentTimeMillis();
        return net;
    }

    public double settleBatch(Long tenantId, Long merchantId, List<Double> amountsYuan) {
        double total = 0;
        for (double amount : amountsYuan) total += submitInstant(tenantId, merchantId, amount, "CNY");
        return total;
    }
}
