package com.acme.clearing.payout.service;

import com.acme.clearing.payout.model.SettlementTicket;

/**
 * 极速放款（新）：小额结算免排队直接出款，大促期间提升商户资金体验。
 */
public class ExpressPayoutService {

    /** 极速通道单笔上限（分）：500 元。 */
    private static final long EXPRESS_LIMIT_FEN = 50_000L;

    private final BankPayoutGateway gateway;

    public ExpressPayoutService(BankPayoutGateway gateway) {
        this.gateway = gateway;
    }

    /** 小额票据免排队，直接出款并落终态。 */
    public void payout(SettlementTicket ticket) {
        if (ticket.getNetAmountFen() > EXPRESS_LIMIT_FEN) {
            throw new IllegalArgumentException("超出极速放款单笔上限");
        }
        boolean accepted = gateway.transfer(ticket.getMerchantId(), ticket.getNetAmountFen());
        if (accepted) {
            ticket.markSuccess();
        } else {
            ticket.markFailed();
        }
    }
}
