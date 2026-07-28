package com.example.settlement.controller;

import com.example.settlement.repository.PayoutCallbackLogRepository;
import java.util.Map;

/**
 * 银行代付结果回调入口。
 */
public class PayoutCallbackController {

    private final PayoutCallbackLogRepository callbackLogs;
    private final SettlementStatusUpdater statusUpdater;

    public PayoutCallbackController(PayoutCallbackLogRepository callbackLogs,
                                    SettlementStatusUpdater statusUpdater) {
        this.callbackLogs = callbackLogs;
        this.statusUpdater = statusUpdater;
    }

    /**
     * 接收银行回调。
     *
     * @param rawBody   原始请求体
     * @param headers   请求头，含签名
     */
    public String onPayoutResult(String rawBody, Map<String, String> headers) {
        Map<String, Object> event = JsonSupport.parse(rawBody);

        String merchantNo = String.valueOf(event.get("merchantNo"));
        String settlementId = String.valueOf(event.get("settlementId"));
        String resultCode = String.valueOf(event.get("resultCode"));

        callbackLogs.save(settlementId, merchantNo, rawBody, resultCode);

        if ("SUCCESS".equals(resultCode)) {
            statusUpdater.markSuccess(Long.valueOf(settlementId));
        } else {
            statusUpdater.markFailed(Long.valueOf(settlementId));
        }

        return "ok";
    }
}
