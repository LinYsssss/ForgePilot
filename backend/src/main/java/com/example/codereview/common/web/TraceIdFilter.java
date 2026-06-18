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
 * Assigns a short trace id to every request and exposes it in MDC and the {@code X-Trace-Id}
 * response header. An inbound {@code X-Trace-Id} is honoured so a caller (or gateway) can correlate
 * across services. Runs before Spring Security so authentication failures are traceable too.
 *
 * <p>The logging pattern ({@code logging.pattern.level}) prints {@code %X{traceId}}, so every log
 * line for a request carries the same id.
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
        // Guard against header/log injection: only allow safe id characters.
        return trimmed.matches("[A-Za-z0-9._-]+") ? trimmed : null;
    }
}
