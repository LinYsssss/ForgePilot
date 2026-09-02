package com.example.settlement.repository;
public class OperationsSettlementQuery {
    public String query(Long merchantId) { return "select * from settlement_request where merchant_id = " + merchantId; }
}
