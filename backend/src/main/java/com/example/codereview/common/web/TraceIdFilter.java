package com.example.codereview.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 给每个请求分配一个短 trace id,并同时暴露在 MDC 与 {@code X-Trace-Id} 响应头上。
 * 入站的 {@code X-Trace-Id} 会被沿用,好让调用方(或网关)能跨服务串起同一条链路。
 * 本过滤器排在 Spring Security 之前,因此鉴权失败同样可追踪。
 *
 * <p>日志格式({@code logging.pattern.level})会打印 {@code %X{traceId}},于是一个请求的每一行
 * 日志都带着同一个 id。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    private static final String HEADER = "X-Trace-Id";
    private static final int MAX_INBOUND_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = sanitize(request.getHeader(HEADER));
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(TRACE_ID, traceId);
        response.setHeader(HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }

    private String sanitize(String inbound) {
        if (inbound == null || inbound.isBlank() || inbound.length() > MAX_INBOUND_LENGTH) {
            return null;
        }
        String trimmed = inbound.trim();
        // 防住 header/日志注入:只放行安全的 id 字符。
        return trimmed.matches("[A-Za-z0-9._-]+") ? trimmed : null;
    }
}
