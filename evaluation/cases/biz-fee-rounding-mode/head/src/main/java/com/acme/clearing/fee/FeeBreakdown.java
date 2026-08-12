package com.acme.clearing.fee;

/** 结算金额三段拆分（单位「分」）。 */
public class FeeBreakdown {

    private final long grossFen;
    private final long feeFen;
    private final long netFen;

    public FeeBreakdown(long grossFen, long feeFen, long netFen) {
        this.grossFen = grossFen;
        this.feeFen = feeFen;
        this.netFen = netFen;
    }

    public long getGrossFen() {
        return grossFen;
    }

    public long getFeeFen() {
        return feeFen;
    }

    public long getNetFen() {
        return netFen;
    }
}
