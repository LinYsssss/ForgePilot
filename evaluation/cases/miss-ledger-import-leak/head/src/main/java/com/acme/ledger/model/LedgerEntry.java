package com.acme.ledger.model;

import java.time.LocalDate;

/** 台账条目。金额单位「分」（long）。 */
public class LedgerEntry {

    private final Long tenantId;
    private final String bizOrderNo;
    private final LocalDate bizDate;
    private final long amountFen;
    private final String direction;

    public LedgerEntry(Long tenantId, String bizOrderNo, LocalDate bizDate,
                       long amountFen, String direction) {
        this.tenantId = tenantId;
        this.bizOrderNo = bizOrderNo;
        this.bizDate = bizDate;
        this.amountFen = amountFen;
        this.direction = direction;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getBizOrderNo() {
        return bizOrderNo;
    }

    public LocalDate getBizDate() {
        return bizDate;
    }

    public long getAmountFen() {
        return amountFen;
    }

    /** IN（收入）/ OUT（支出）。 */
    public String getDirection() {
        return direction;
    }
}
