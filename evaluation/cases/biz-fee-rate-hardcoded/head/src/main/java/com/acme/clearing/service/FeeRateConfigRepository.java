package com.acme.clearing.service;

import java.util.Optional;

/** 商户费率配置读取。费率单位为基点（万分之一），数据来自 fee_rate_config 表。 */
public interface FeeRateConfigRepository {

    Optional<Integer> findEffectiveFeeRateBp(Long tenantId, Long merchantId);
}
