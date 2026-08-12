package com.acme.clearing.service;

import com.acme.clearing.model.ClearingRecord;
import com.acme.clearing.repository.ClearingRecordStore;
import java.util.Optional;

/**
 * 常规清分（T+1）。
 *
 * <p>金额全程 long（分），费率从配置读取（基点），手续费向下取整，
 * 最小净额 100 分，仅支持 CNY。
 */
public class ClearingService {

    private static final long MIN_NET_FEN = 100L;

    private final ClearingRecordStore store;
    private final RateConfigGateway rateConfigs;

    public ClearingService(ClearingRecordStore store, RateConfigGateway rateConfigs) {
        this.store = store;
        this.rateConfigs = rateConfigs;
    }

    public ClearingRecord submit(Long tenantId, Long merchantId, String idempotencyKey,
                                 long grossFen, String currency) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("缺少幂等键");
        }
        if (!"CNY".equals(currency)) {
            throw new IllegalArgumentException("当前仅支持 CNY 清分，收到: " + currency);
        }

        Optional<ClearingRecord> existing = store.findByIdempotencyKey(tenantId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        long feeFen = computeFee(tenantId, merchantId, grossFen);
        if (grossFen - feeFen < MIN_NET_FEN) {
            throw new IllegalStateException("清分净额低于最小值，本期不发起");
        }

        ClearingRecord record = new ClearingRecord(
                tenantId, merchantId, idempotencyKey, grossFen, feeFen, currency);
        record.markProcessing();
        return store.save(record);
    }

    long computeFee(Long tenantId, Long merchantId, long grossFen) {
        if (grossFen < 0) {
            throw new IllegalArgumentException("清分金额不能为负");
        }
        int rateBp = rateConfigs.findEffectiveRateBp(tenantId, merchantId)
                .orElseThrow(() -> new IllegalStateException("商户未配置费率: " + merchantId));
        // 基点万分之一；整数除法向下取整，差额平台承担。
        return grossFen * rateBp / 10_000L;
    }
}
