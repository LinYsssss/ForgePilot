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
    /** 登录是唯一无需认证即可反复尝试的写端点,按通用额度(默认 120/min)足以爆破弱口令,单独收紧。 */
    private static final String LOGIN_PATH = "/api/auth/login";

    private final boolean enabled;
    private final int limit;
    private final int loginLimit;
    private final long windowMs;
    private final ObjectMapper objectMapper;
    private final ClientIpResolver clientIpResolver;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(boolean enabled, int limit, int windowSeconds, ObjectMapper objectMapper) {
        this(enabled, limit, windowSeconds, 8, objectMapper, new ClientIpResolver(""));
    }

    public RateLimitFilter(boolean enabled, int limit, int windowSeconds, int loginLimit, ObjectMapper objectMapper) {
        this(enabled, limit, windowSeconds, loginLimit, objectMapper, new ClientIpResolver(""));
    }

    public RateLimitFilter(boolean enabled, int limit, int windowSeconds, int loginLimit,
                           ObjectMapper objectMapper, ClientIpResolver clientIpResolver) {
        this.enabled = enabled;
        this.limit = limit;
        this.loginLimit = loginLimit;
        this.windowMs = Math.max(1, windowSeconds) * 1000L;
        this.objectMapper = objectMapper;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!enabled || limit <= 0 || !request.getRequestURI().startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }
        boolean login = LOGIN_PATH.equals(request.getRequestURI());
        int effectiveLimit = login ? loginLimit : limit;
        long now = System.currentTimeMillis();
        // 登录单独计数,避免同一 IP 的正常读请求把爆破额度顶掉(或反过来)。
        Window window = windows.compute((login ? "login:" : "") + resolveKey(request), (key, existing) -> {
            if (existing == null || now - existing.start >= windowMs) {
                return new Window(now);
            }
            existing.count++;
            return existing;
        });
        if (windows.size() > MAX_TRACKED_KEYS) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().start >= windowMs);
        }
        if (window.count > effectiveLimit) {
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
        // 只有当直连对端是受信代理时才采信 X-Forwarded-For,否则任何人都能伪造该头换桶。
        return clientIpResolver.resolve(request);
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
