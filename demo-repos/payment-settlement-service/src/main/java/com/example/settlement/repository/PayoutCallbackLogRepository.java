package com.example.settlement.repository;

/** 银行回调日志存储。 */
public interface PayoutCallbackLogRepository {

    void save(String settlementId, String merchantNo, String payload, String resultCode);
}
