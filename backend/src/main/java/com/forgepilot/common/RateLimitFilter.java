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
 * <p>地址取 {@code getRemoteAddr()}，而它是否等于真实客户端取决于
 * {@code server.forward-headers-strategy}。本部署开了 {@code native}，因为 nginx 是唯一
 * 入口且会把真实地址追加进 {@code X-Forwarded-For}；Tomcat 的 RemoteIpValve 只信任内网
 * 网段的代理并从右往左取第一个非内网地址，所以客户端伪造的 XFF 拿不到信任。**不开它**
 * 才是危险的：那时每个请求都记成 nginx 那一个地址，配额变成全体共享，而丢掉的 webhook
 * 投递不会被 provider 重试。
 *
 * <p>这一层仍是<strong>纵深防御而非主要手段</strong>——真正的流量整形属于代理侧。
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final ApiError TOO_MANY =
            new ApiError("too_many_requests", "Too many requests. Try again shortly.", "");

    private final String name;
    private final RateLimiter limiter;
    private final ObjectMapper json;
    private final PathPredicate applies;

    /**
     * @param name    本实例的名字，只用于区分「已过此过滤器」标记，见
     *                {@link #getAlreadyFilteredAttributeName()}
     * @param applies 判断某个请求是否受本过滤器约束。传入而不是写死，
     *                是因为两条安全过滤器链要限的路径与配额都不同。
     */
    public RateLimitFilter(String name, RateLimiter limiter, ObjectMapper json, PathPredicate applies) {
        this.name = name;
        this.limiter = limiter;
        this.json = json;
        this.applies = applies;
    }

    /**
     * <strong>必须按实例区分。</strong>基类默认用<em>类名</em>做「本请求已过此过滤器」的标记，
     * 而同一条链上装着本类的两个实例；第一个标记之后，第二个会在<em>每个</em>请求上整个跳过
     * 自己，且不留任何痕迹——注册限流曾因此完全失效而注册照常返回 201。
     */
    @Override
    protected String getAlreadyFilteredAttributeName() {
        return getClass().getName() + "." + name + ".FILTERED";
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
