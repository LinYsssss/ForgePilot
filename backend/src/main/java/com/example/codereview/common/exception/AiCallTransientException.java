package com.example.codereview.common.exception;

/**
 * 标记「值得重试」的 AI provider 瞬时故障(网络错误、读超时、HTTP 5xx,或 429 限流)。
 * Resilience4j 只针对本类型做重试与熔断;确定性失败(参数非法、鉴权错误、输出无法解析)
 * 一律留在 {@link com.example.codereview.common.exception.BusinessException},绝不重试。
 */
public class AiCallTransientException extends RuntimeException {

    public AiCallTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
