package com.example.settlement.service;

import java.util.Optional;

/** 商户费率配置读取。费率单位为基点（万分之一）。 */
public interface MerchantFeeConfigRepository {

    Optional<Integer> findEffectiveFeeRateBp(Long tenantId, Long merchantId);
}
