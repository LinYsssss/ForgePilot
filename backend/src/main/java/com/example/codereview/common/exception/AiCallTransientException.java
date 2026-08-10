package com.example.codereview.common.exception;

/**
 * Marks a transient AI-provider failure (network error, read timeout, HTTP 5xx, or 429 rate-limit)
 * that is worth retrying. Resilience4j is configured to retry and circuit-break on this type only;
 * deterministic failures (bad request, auth error, unparseable output) stay as
 * {@link com.example.codereview.common.exception.BusinessException} and are never retried.
 */
public class AiCallTransientException extends RuntimeException {

    public AiCallTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
