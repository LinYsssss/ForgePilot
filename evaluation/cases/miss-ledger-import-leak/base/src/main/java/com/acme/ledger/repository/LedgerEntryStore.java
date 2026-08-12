package com.acme.ledger.repository;

import com.acme.ledger.model.LedgerEntry;
import java.time.LocalDate;
import java.util.List;

/** 台账存储。落地实现全部参数化查询。 */
public interface LedgerEntryStore {

    void batchInsert(List<LedgerEntry> entries);

    boolean existsByBizOrderNo(Long tenantId, String bizOrderNo);

    List<LedgerEntry> listByDate(Long tenantId, LocalDate bizDate);
}
