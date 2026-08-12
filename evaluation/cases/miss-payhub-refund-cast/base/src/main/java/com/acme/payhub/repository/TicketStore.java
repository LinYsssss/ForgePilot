package com.acme.payhub.repository;

import com.acme.payhub.model.SettlementTicket;
import java.util.Optional;

/** 结算工单存储。 */
public interface TicketStore {

    SettlementTicket save(SettlementTicket ticket);

    Optional<SettlementTicket> findByIdempotencyKey(Long tenantId, String idempotencyKey);
}
