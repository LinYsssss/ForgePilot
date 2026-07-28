package com.example.codereview.common.api;

import com.example.codereview.common.web.TraceIdFilter;
import org.slf4j.MDC;

/**
 * Envelope for every JSON endpoint.
 *
 * <p>Phase 0 added {@code errorCode} and {@code traceId} alongside the original numeric
 * {@code code}. The numeric field is deliberately left in place: the frontend still branches on
 * {@code code !== 0}, and keeping it means neither workstream has to synchronise a breaking
 * response change with the other. New code should set a stable {@link ErrorCode} and clients should
 * migrate to reading {@code errorCode}; the numeric field can be retired once nothing reads it.
 */
public record ApiResponse<T>(int code, String errorCode, String message, String traceId, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, ErrorCode.OK.name(), "success", currentTraceId(), data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, ErrorCode.OK.name(), "success", currentTraceId(), null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return error(errorCode, errorCode.defaultMessage());
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.legacyCode(), errorCode.name(), message, currentTraceId(), null);
    }

    /**
     * Legacy entry point for call sites that still carry a raw numeric code. Derives a best-effort
     * string code so responses stay uniform during the incremental migration.
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, ErrorCode.fromLegacy(code).name(), message, currentTraceId(), null);
    }

    /**
     * Keeps an already-decided numeric code while attaching an explicit string identifier. Used by
     * the exception handler, where the numeric code and HTTP status come from the thrown exception
     * and must not be re-derived — some legacy codes (6002 and friends) do not map onto their own
     * HTTP status.
     */
    public static <T> ApiResponse<T> error(int code, ErrorCode errorCode, String message) {
        return new ApiResponse<>(code, errorCode.name(), message, currentTraceId(), null);
    }

    private static String currentTraceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
