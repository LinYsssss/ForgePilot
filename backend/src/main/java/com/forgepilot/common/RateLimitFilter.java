package com.forgepilot.common;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * 按客户端地址限流一组路径。超出配额时直接以 429 与唯一错误体结构作答，
 * 不进入后续过滤器，也不进入 Spring MVC。
 *
 * <p>它必须自己写响应体：和安全过滤器链的 401/403 一样，这里的拒绝发生在
 * Spring MVC <strong>之前</strong>，因此永远到不了 {@link ApiExceptionHandler}。
 * 若不在此统一处理，它就会逃出 ARCHITECTURE.md 2.4 规定的唯一错误体结构。
 *
 * <p><strong>刻意不信任 {@code X-Forwarded-For}</strong>：那个头可任意伪造，采信它就等于
 * 每次换个假地址即可绕开，比没有限流更糟——它给出「已防护」的错觉。代价是反向代理之后
 * 全部请求共享代理那一个地址的配额，所以这一层是<strong>纵深防御而非主要手段</strong>：
 * 按真实客户端地址限流应配在代理侧，本层只保证代理没配时登录端点不再完全裸露。
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final ApiError TOO_MANY =
            new ApiError("too_many_requests", "Too many requests. Try again shortly.", "");

    private final RateLimiter limiter;
    private final ObjectMapper json;
    private final PathPredicate applies;

    /**
     * @param applies 判断某个请求是否受本过滤器约束。传入而不是写死，
     *                是因为两条安全过滤器链要限的路径与配额都不同。
     */
    public RateLimitFilter(RateLimiter limiter, ObjectMapper json, PathPredicate applies) {
        this.limiter = limiter;
        this.json = json;
        this.applies = applies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!applies.test(request.getMethod(), request.getRequestURI())
                || limiter.tryAcquire(request.getRemoteAddr())) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(TOO_MANY));
    }

    /** 「这个请求受不受限」。方法与路径都给出，因为只有 POST 的登录需要限，GET 不需要。 */
    public interface PathPredicate {
        boolean test(String method, String path);
    }
}
