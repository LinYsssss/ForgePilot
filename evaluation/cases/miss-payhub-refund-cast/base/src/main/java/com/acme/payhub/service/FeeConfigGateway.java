package com.acme.payhub.service;

import java.util.Optional;

/** 商户费率配置读取口，实际实现走 merchant_fee_config 表。 */
public interface FeeConfigGateway {

    Optional<Integer> findEffectiveFeeRateBp(Long tenantId, Long merchantId);
}
