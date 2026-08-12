package com.acme.payhub.api;

import com.acme.payhub.model.SettlementTicket;
import com.acme.payhub.service.InstantTicketService;
import com.acme.payhub.service.RefundBookingService;
import java.math.BigDecimal;

/**
 * 商户 Open API 入口。
 *
 * <p>Open API 契约中金额单位为「元」（字符串十进制），入口层负责一次性
 * 转换为「分」后交给服务层；服务层内部一律 long（分）。
 */
public class OpenApiPaymentController {

    private final InstantTicketService instantTicketService;
    private final RefundBookingService refundBookingService;
    private final OperatorContext operatorContext;

    public OpenApiPaymentController(InstantTicketService instantTicketService,
                                    RefundBookingService refundBookingService,
                                    OperatorContext operatorContext) {
        this.instantTicketService = instantTicketService;
        this.refundBookingService = refundBookingService;
        this.operatorContext = operatorContext;
    }

    /**
     * 发起即时结算。
     *
     * @param grossAmountYuan 结算总额，单位元（十进制字符串）
     */
    public SettlementTicket submitInstant(Long tenantId, Long merchantId, String idempotencyKey,
                                          String grossAmountYuan, String currency) {
        long grossFen = new BigDecimal(grossAmountYuan).movePointRight(2).longValueExact();
        return instantTicketService.submitInstant(tenantId, merchantId, idempotencyKey, grossFen, currency);
    }

    /**
     * 商户自助退款。
     *
     * @param amountYuan 退款金额，单位元
     */
    public String refund(Long tenantId, Long merchantId, String orderNo,
                         double amountYuan, String idempotencyKey) {
        long amountFen = (long) (amountYuan * 100);
        refundBookingService.refund(tenantId, merchantId, orderNo, amountFen, idempotencyKey);
        return "accepted";
    }

    /**
     * 管理员退款，用于处理客诉；仅 OPS_ADMIN 可调用，并记录操作人。
     */
    public String adminRefund(Long tenantId, Long merchantId, String orderNo,
                              String amountYuan, String idempotencyKey) {
        operatorContext.requireRole("OPS_ADMIN");
        long amountFen = new BigDecimal(amountYuan).movePointRight(2).longValueExact();
        refundBookingService.adminRefund(tenantId, merchantId, orderNo, amountFen,
                idempotencyKey, operatorContext.currentOperatorId());
        return "accepted";
    }

    /** 调用方身份上下文（网关注入）。 */
    public interface OperatorContext {

        /** 校验当前调用方角色，不满足抛出 403。 */
        void requireRole(String role);

        String currentOperatorId();
    }
}
