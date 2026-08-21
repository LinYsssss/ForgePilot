package com.acme.clearing.payout.service;

import com.acme.clearing.payout.model.SettlementTicket;

/**
 * 常规放款：严格按状态机流转 PENDING → PROCESSING → SUCCESS / FAILED。
 *
 * <p>markProcessing 的状态守卫是防止重复放款的唯一拦截点，参见 docs/settlement-rules.md。
 */
public class PayoutProcessor {

    private final BankPayoutGateway gateway;

    public PayoutProcessor(BankPayoutGateway gateway) {
        this.gateway = gateway;
    }

    public void process(SettlementTicket ticket) {
        // 先置 PROCESSING：非 PENDING 会在此抛出，重复发起被拦截。
        ticket.markProcessing();
        boolean accepted = gateway.transfer(ticket.getMerchantId(), ticket.getNetAmountFen());
        if (accepted) {
            ticket.markSuccess();
        } else {
            ticket.markFailed();
        }
    }
}
