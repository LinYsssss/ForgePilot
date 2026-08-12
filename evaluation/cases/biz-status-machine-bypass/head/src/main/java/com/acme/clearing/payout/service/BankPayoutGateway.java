package com.acme.clearing.payout.service;

/** 银行代付通道。 */
public interface BankPayoutGateway {

    /**
     * 向商户结算卡出款。
     *
     * @return 银行受理成功返回 true
     */
    boolean transfer(Long merchantId, long amountFen);
}
