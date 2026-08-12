package com.acme.payhub.service;

/**
 * 退款入账。
 *
 * <p>退款金额单位「分」（long），幂等键必填；管理员通道要求已通过角色
 * 校验并记录审计流水后才可调用。
 */
public class RefundBookingService {

    private final RefundAuditSink auditSink;

    public RefundBookingService(RefundAuditSink auditSink) {
        this.auditSink = auditSink;
    }

    /**
     * 商户自助退款。
     *
     * @param amountFen 退款金额，单位「分」
     */
    public void refund(Long tenantId, Long merchantId, String orderNo,
                       long amountFen, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("退款必须携带幂等键");
        }
        if (amountFen <= 0) {
            throw new IllegalArgumentException("退款金额必须为正");
        }
        auditSink.record("REFUND", tenantId, merchantId, orderNo, amountFen);
        dispatchPayout(tenantId, merchantId, orderNo, amountFen, idempotencyKey);
    }

    /**
     * 管理员通道退款：调用方（Controller 层）已完成 OPS_ADMIN 角色校验，
     * 此处再落一条独立审计。
     */
    public void adminRefund(Long tenantId, Long merchantId, String orderNo,
                            long amountFen, String idempotencyKey, String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("管理员退款必须携带操作人");
        }
        auditSink.record("ADMIN_REFUND[" + operatorId + "]", tenantId, merchantId, orderNo, amountFen);
        refund(tenantId, merchantId, orderNo, amountFen, idempotencyKey);
    }

    private void dispatchPayout(Long tenantId, Long merchantId, String orderNo,
                                long amountFen, String idempotencyKey) {
        // 代付网关对同一幂等键保证至多一次出款。
    }

    /** 审计流水写入口。 */
    public interface RefundAuditSink {
        void record(String action, Long tenantId, Long merchantId, String orderNo, long amountFen);
    }
}
