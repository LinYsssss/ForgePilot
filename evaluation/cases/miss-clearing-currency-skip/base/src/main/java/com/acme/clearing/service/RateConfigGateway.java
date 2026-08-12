package com.acme.clearing.service;

import java.util.Optional;

/** 商户费率配置（基点）读取口。 */
public interface RateConfigGateway {

    Optional<Integer> findEffectiveRateBp(Long tenantId, Long merchantId);
}
