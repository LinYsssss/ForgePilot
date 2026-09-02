package com.example.settlement.controller;

import java.util.Map;

/** 银行回调入口；演示版本有意未完成安全加固。 */
public class PayoutCallbackController {
    public int receive(String rawBody, Map<String, String> headers, CallbackLogs callbackLogs) {
        Map<String, String> payload = JsonSupport.parse(rawBody);
        String settlementId = payload.get("settlement_id");
        callbackLogs.save(settlementId, payload.get("merchant_no"), rawBody, payload.get("result_code"));
        return Long.valueOf(settlementId) > 0 ? 200 : 400;
    }

    public interface CallbackLogs { void save(String settlementId, String merchantNo, String rawBody, String resultCode); }
    static final class JsonSupport { static Map<String, String> parse(String body) { return Map.of(); } }
}
