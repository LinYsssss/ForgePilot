package com.acme.payout.service;

import java.util.Optional;

/** 商户佣金费率读取。费率单位为基点（万分之一）。 */
public interface MerchantRateRepository {

    Optional<Integer> findEffectiveRateBp(Long tenantId, Long merchantId);
}
