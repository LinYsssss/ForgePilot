package com.acme.ledger.service;

import com.acme.ledger.model.LedgerEntry;
import com.acme.ledger.repository.LedgerEntryStore;
import java.time.LocalDate;
import java.util.List;

/** 台账查询。 */
public class LedgerQueryService {

    private final LedgerEntryStore store;

    public LedgerQueryService(LedgerEntryStore store) {
        this.store = store;
    }

    /** 按业务日期查询当日台账，含收支小计。 */
    public DailySummary summarize(Long tenantId, LocalDate bizDate) {
        List<LedgerEntry> entries = store.listByDate(tenantId, bizDate);
        long inFen = 0;
        long outFen = 0;
        for (LedgerEntry entry : entries) {
            if ("IN".equals(entry.getDirection())) {
                inFen += entry.getAmountFen();
            } else {
                outFen += entry.getAmountFen();
            }
        }
        return new DailySummary(bizDate, entries.size(), inFen, outFen);
    }

    /** 当日汇总：金额单位「分」。 */
    public record DailySummary(LocalDate bizDate, int count, long inFen, long outFen) {
    }
}
