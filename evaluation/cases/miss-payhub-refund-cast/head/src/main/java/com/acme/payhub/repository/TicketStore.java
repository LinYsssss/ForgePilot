package com.acme.payhub.repository;

import com.acme.payhub.model.SettlementTicket;
import java.util.Optional;

/** 结算工单与回调审计存储。 */
public interface TicketStore {

    SettlementTicket save(SettlementTicket ticket);

    Optional<SettlementTicket> findByIdempotencyKey(Long tenantId, String idempotencyKey);

    Optional<SettlementTicket> findById(Long ticketId);

    /** 回调去重：同一 callbackId 只处理一次。 */
    boolean callbackAlreadyHandled(String callbackId);

    /** 记录回调审计（脱敏后的摘要，不落原文）。 */
    void saveCallbackAudit(String callbackId, Long ticketId, String resultCode, String payloadDigest);
}
