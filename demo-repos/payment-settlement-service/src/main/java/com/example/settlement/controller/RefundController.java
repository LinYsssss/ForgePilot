package com.example.settlement.controller;

import com.example.settlement.service.RefundService;

/**
 * 商户退款入口。
 */
public class RefundController {

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    /**
     * 发起退款。
     *
     * @param amountYuan 退款金额，单位元
     */
    public String refund(Long tenantId, Long merchantId, String orderNo, double amountYuan) {
        long amountFen = (long) (amountYuan * 100);
        refundService.refund(tenantId, merchantId, orderNo, amountFen);
        return "accepted";
    }

    /**
     * 管理员强制退款，用于处理客诉。
     */
    public String forceRefund(Long tenantId, Long merchantId, String orderNo, double amountYuan) {
        long amountFen = (long) (amountYuan * 100);
        refundService.forceRefund(tenantId, merchantId, orderNo, amountFen);
        return "accepted";
    }
}
