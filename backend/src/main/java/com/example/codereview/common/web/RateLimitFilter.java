package com.example.codereview.common.web;

import com.example.codereview.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window rate limiter for {@code /api/**}, keyed per authenticated user (falling back to
 * client IP for anonymous traffic such as login attempts). In-memory and single-instance by design
 * — the project intentionally avoids Redis; this caps abuse and protects the LLM budget without
 * extra infrastructure. Wired into the Spring Security chain after authentication so the user key
 * is available.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_TRACKED_KEYS = 5000;

    private final boolean enabled;
    private final int limit;
    private final long windowMs;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(boolean enabled, int limit, int windowSeconds, ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.limit = limit;
        this.windowMs = Math.max(1, windowSeconds) * 1000L;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!enabled || limit <= 0 || !request.getRequestURI().startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }
        long now = System.currentTimeMillis();
        Window window = windows.compute(resolveKey(request), (key, existing) -> {
            if (existing == null || now - existing.start >= windowMs) {
                return new Window(now);
            }
            existing.count++;
            return existing;
        });
        if (windows.size() > MAX_TRACKED_KEYS) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().start >= windowMs);
        }
        if (window.count > limit) {
            rejectTooMany(response, now, window.start);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void rejectTooMany(HttpServletResponse response, long now, long windowStart) throws IOException {
        long retryAfterSeconds = Math.max(1, (windowMs - (now - windowStart)) / 1000);
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(429, "请求过于频繁，请稍后再试")));
    }

    private String resolveKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken)
                && auth.getName() != null) {
            return "user:" + auth.getName();
        }
        return "ip:" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static final class Window {
        private final long start;
        private int count;

        private Window(long start) {
            this.start = start;
            this.count = 1;
        }
    }
}
