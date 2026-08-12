package com.acme.clearing.repository;

import com.acme.clearing.model.ClearingRecord;
import java.util.Optional;

/** 清分记录存储。 */
public interface ClearingRecordStore {

    ClearingRecord save(ClearingRecord record);

    Optional<ClearingRecord> findByIdempotencyKey(Long tenantId, String idempotencyKey);
}
