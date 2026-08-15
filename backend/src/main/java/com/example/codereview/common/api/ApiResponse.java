package com.example.codereview.common.api;

import com.example.codereview.common.web.TraceIdFilter;
import org.slf4j.MDC;

/**
 * 所有 JSON 端点的统一信封。
 *
 * <p>Phase 0 在原有数字 {@code code} 之外补了 {@code errorCode} 与 {@code traceId}。
 * 数字字段是**刻意留着**的:前端仍然按 {@code code !== 0} 分支,留着它就不必让前后端
 * 同步做一次破坏性的响应变更。新代码应设置稳定的 {@link ErrorCode},客户端逐步改读
 * {@code errorCode};等到没有任何消费者再读数字字段时,它才可以退役。
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
     * 遗留入口,供仍然只持有裸数字码的调用点使用。尽力反推出一个字符串码,
     * 让增量迁移期间的响应保持一致形状。
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, ErrorCode.fromLegacy(code).name(), message, currentTraceId(), null);
    }

    /**
     * 保留已经决定好的数字码,同时挂上显式的字符串标识。异常处理器用它——那里的数字码与
     * HTTP 状态都来自抛出的异常,不能再推导一遍:部分遗留码(6002 之流)并不映射到与自己同名的
     * HTTP 状态。
     */
    public static <T> ApiResponse<T> error(int code, ErrorCode errorCode, String message) {
        return new ApiResponse<>(code, errorCode.name(), message, currentTraceId(), null);
    }

    private static String currentTraceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
