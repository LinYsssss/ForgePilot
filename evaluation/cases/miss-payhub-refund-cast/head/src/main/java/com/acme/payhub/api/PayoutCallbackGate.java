package com.acme.payhub.api;

import com.acme.payhub.model.SettlementTicket;
import com.acme.payhub.repository.TicketStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 银行代付结果回调入口。
 *
 * <p>安全基线：先验签（HMAC-SHA256，密钥从配置注入）、再按 callbackId
 * 幂等去重、审计只落脱敏摘要不落原文。
 */
public class PayoutCallbackGate {

    private final TicketStore ticketStore;
    private final SignatureVerifier signatureVerifier;

    public PayoutCallbackGate(TicketStore ticketStore, SignatureVerifier signatureVerifier) {
        this.ticketStore = ticketStore;
        this.signatureVerifier = signatureVerifier;
    }

    public String onPayoutResult(String rawBody, Map<String, String> headers, Map<String, Object> event) {
        String signature = headers.get("X-Payout-Signature");
        if (!signatureVerifier.verify(rawBody, signature)) {
            throw new IllegalStateException("回调验签失败，拒绝处理");
        }

        String callbackId = String.valueOf(event.get("callbackId"));
        if (ticketStore.callbackAlreadyHandled(callbackId)) {
            return "duplicate";
        }

        Long ticketId = Long.valueOf(String.valueOf(event.get("ticketId")));
        String resultCode = String.valueOf(event.get("resultCode"));

        SettlementTicket ticket = ticketStore.findById(ticketId)
                .orElseThrow(() -> new IllegalStateException("回调指向的工单不存在: " + ticketId));

        if ("SUCCESS".equals(resultCode)) {
            ticket.markSuccess();
        } else {
            ticket.markFailed();
        }
        ticketStore.save(ticket);
        ticketStore.saveCallbackAudit(callbackId, ticketId, resultCode, digest(rawBody));
        return "ok";
    }

    private String digest(String rawBody) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(rawBody.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("摘要计算失败", ex);
        }
    }

    /** 验签器：HMAC 密钥由部署配置注入，代码与日志中不出现。 */
    public interface SignatureVerifier {
        boolean verify(String rawBody, String signature);
    }
}
