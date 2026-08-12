package com.acme.clearing.api;

import com.acme.clearing.model.ClearingRecord;
import com.acme.clearing.service.InstantClearingService;
import java.math.BigDecimal;
import java.util.List;

/**
 * 即时清分开放入口。
 *
 * <p>Open API 金额单位为「元」（十进制字符串），入口层用 BigDecimal 精确
 * 转「分」，超精度直接拒绝；批量入口仅限 OPS_ADMIN。
 */
public class InstantClearingEndpoint {

    private final InstantClearingService instantClearingService;
    private final CallerContext callerContext;

    public InstantClearingEndpoint(InstantClearingService instantClearingService,
                                   CallerContext callerContext) {
        this.instantClearingService = instantClearingService;
        this.callerContext = callerContext;
    }

    /** 单笔即时清分。 */
    public ClearingRecord submit(Long tenantId, Long merchantId, String idempotencyKey,
                                 String grossYuan, String currency) {
        long grossFen = toFen(grossYuan);
        return instantClearingService.submitInstant(
                tenantId, merchantId, idempotencyKey, grossFen, currency);
    }

    /** 批量即时清分，仅运营管理员可用。 */
    public int submitBatch(Long tenantId, List<InstantClearingService.BatchItem> items) {
        callerContext.requireRole("OPS_ADMIN");
        return instantClearingService.submitBatch(tenantId, items);
    }

    private long toFen(String amountYuan) {
        try {
            return new BigDecimal(amountYuan).movePointRight(2).longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("金额精度超出「分」，拒绝处理: " + amountYuan);
        }
    }

    /** 调用方身份上下文（网关注入）。 */
    public interface CallerContext {

        /** 校验角色，不满足抛 403。 */
        void requireRole(String role);
    }
}
