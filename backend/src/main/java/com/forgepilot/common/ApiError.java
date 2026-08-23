package com.forgepilot.common;

/** 全部 API 失败响应的唯一错误体结构（ARCHITECTURE.md 2.4）。 */
public record ApiError(String code, String message, String traceId) {
}
