package com.acme.clearing.api;

import com.acme.clearing.model.ClearingRecord;
import com.acme.clearing.repository.ClearingRecordStore;
import java.util.Map;
import java.util.Optional;

/**
 * 银行代付结果回调入口。先验签、再按事件号去重、只落脱敏摘要。
 */
public class PayoutResultEndpoint {

    private final ClearingRecordStore store;
    private final HmacVerifier hmacVerifier;
    private final EventDedup eventDedup;

    public PayoutResultEndpoint(ClearingRecordStore store, HmacVerifier hmacVerifier,
                                EventDedup eventDedup) {
        this.store = store;
        this.hmacVerifier = hmacVerifier;
        this.eventDedup = eventDedup;
    }

    public String onResult(String rawBody, Map<String, String> headers, Map<String, Object> event) {
        if (!hmacVerifier.verify(rawBody, headers.get("X-Clearing-Signature"))) {
            throw new IllegalStateException("回调验签失败，拒绝处理");
        }
        String eventId = String.valueOf(event.get("eventId"));
        if (!eventDedup.tryAcquire(eventId)) {
            return "duplicate";
        }

        Long tenantId = Long.valueOf(String.valueOf(event.get("tenantId")));
        String idempotencyKey = String.valueOf(event.get("idempotencyKey"));
        Optional<ClearingRecord> record = store.findByIdempotencyKey(tenantId, idempotencyKey);
        if (record.isEmpty()) {
            throw new IllegalStateException("回调指向的清分记录不存在: " + idempotencyKey);
        }

        if ("SUCCESS".equals(String.valueOf(event.get("resultCode")))) {
            record.get().markSuccess();
        } else {
            record.get().markFailed();
        }
        store.save(record.get());
        return "ok";
    }

    /** HMAC 验签器：密钥由部署配置注入，不出现在代码与日志。 */
    public interface HmacVerifier {
        boolean verify(String rawBody, String signature);
    }

    /** 事件去重：同一 eventId 仅第一次返回 true。 */
    public interface EventDedup {
        boolean tryAcquire(String eventId);
    }
}
