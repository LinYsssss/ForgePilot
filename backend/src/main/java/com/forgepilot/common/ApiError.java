package com.forgepilot.common;

/** The single error body shape for every API failure (ARCHITECTURE.md 2.4). */
public record ApiError(String code, String message, String traceId) {
}
