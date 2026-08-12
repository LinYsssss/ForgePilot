package com.acme.clearing.batch;

/** 商户当日可结算余额快照。 */
public class MerchantBalance {

    private final Long merchantId;
    /** 可结算总额（分） */
    private final long availableFen;

    public MerchantBalance(Long merchantId, long availableFen) {
        this.merchantId = merchantId;
        this.availableFen = availableFen;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public long getAvailableFen() {
        return availableFen;
    }
}
