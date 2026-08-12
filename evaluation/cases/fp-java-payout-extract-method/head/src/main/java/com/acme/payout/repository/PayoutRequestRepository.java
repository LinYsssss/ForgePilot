package com.acme.payout.repository;

import com.acme.payout.model.PayoutRequest;
import java.util.Optional;

/** 打款请求读写。实现层所有查询强制携带 tenant_id 过滤。 */
public interface PayoutRequestRepository {

    Optional<PayoutRequest> findByRequestKey(Long tenantId, String requestKey);

    PayoutRequest save(PayoutRequest request);
}
