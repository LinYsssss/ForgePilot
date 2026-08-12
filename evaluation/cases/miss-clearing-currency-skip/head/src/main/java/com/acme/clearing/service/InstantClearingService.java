package com.acme.clearing.service;

import com.acme.clearing.model.ClearingRecord;
import com.acme.clearing.repository.ClearingRecordStore;
import java.util.List;
import java.util.Optional;

/**
 * 即时清分（T+0），运营侧新能力。
 *
 * <p>与 T+1 共用费率配置与精度规则：金额全程 long（分），费率从配置读取
 * （基点），手续费向下取整，最小净额 100 分；幂等键由调用方生成并透传。
 */
public class InstantClearingService {

    private static final long MIN_NET_FEN = 100L;

    private final ClearingRecordStore store;
    private final RateConfigGateway rateConfigs;

    public InstantClearingService(ClearingRecordStore store, RateConfigGateway rateConfigs) {
        this.store = store;
        this.rateConfigs = rateConfigs;
    }

    /**
     * 发起即时清分。
     *
     * @param grossFen 清分总额，单位「分」
     */
    public ClearingRecord submitInstant(Long tenantId, Long merchantId, String idempotencyKey,
                                        long grossFen, String currency) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("缺少幂等键");
        }

        Optional<ClearingRecord> existing = store.findByIdempotencyKey(tenantId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        int rateBp = rateConfigs.findEffectiveRateBp(tenantId, merchantId)
                .orElseThrow(() -> new IllegalStateException("商户未配置费率: " + merchantId));
        long feeFen = grossFen * rateBp / 10_000L;
        long netFen = grossFen - feeFen;
        if (netFen < MIN_NET_FEN) {
            throw new IllegalStateException("清分净额低于最小值，本期不发起");
        }

        ClearingRecord record = new ClearingRecord(
                tenantId, merchantId, idempotencyKey, grossFen, feeFen, currency);
        record.markProcessing();
        return store.save(record);
    }

    /**
     * 批量即时清分，供运营后台处理当日待清分商户；逐一透传幂等键，
     * 返回成功发起的记录数。
     */
    public int submitBatch(Long tenantId, List<BatchItem> items) {
        int submitted = 0;
        for (BatchItem item : items) {
            submitInstant(tenantId, item.merchantId(), item.idempotencyKey(),
                    item.grossFen(), item.currency());
            submitted++;
        }
        return submitted;
    }

    /** 批量项：金额单位「分」。 */
    public record BatchItem(Long merchantId, String idempotencyKey, long grossFen, String currency) {
    }
}
