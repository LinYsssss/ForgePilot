package com.example.codereview.common.web;

import com.example.codereview.auth.AuthCookieService;
import com.example.codereview.common.security.CurrentUser;
import com.example.codereview.common.security.SecurityAuditLogger;
import com.example.codereview.common.security.SecurityAuditLogger.Outcome;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 兜底的安全审计:把「谁改了什么、谁被拒了」记成统一事件。
 *
 * <p>为什么用过滤器而不是在每个 Service 里埋点:写操作分布在十几个控制器上,逐个埋点既漏得快
 * 也改得多;而拒绝(401/403)是 Spring Security 在进入控制器之前就产生的,业务代码根本看不到。
 * 过滤器装在安全过滤链**之外**,因此能看到最终状态码,也覆盖到被拦在链上的请求。
 *
 * <p>登录/退出有各自更精确的事件(带失败原因),这里跳过以免重复;webhook 投递本身有
 * {@code scm_webhook_delivery} 表作为审计载体,其安全事件由控制器显式记录,这里同样跳过。
 *
 * <p>未带任何凭据的 401 不记:未登录时打开页面就会产生一条,信噪比太低。带了凭据却被拒的
 * 401(令牌过期、伪造、会话版本失效)才是信号,403 一律记录——那是**已认证**身份的越权尝试。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityAuditFilter extends OncePerRequestFilter {

    private static final Set<String> SELF_AUDITED = Set.of("/api/auth/login", "/api/auth/logout");
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final SecurityAuditLogger audit;
    private final ClientIpResolver clientIpResolver;
    private final AuthCookieService authCookieService;

    public SecurityAuditFilter(SecurityAuditLogger audit,
                               AuthCookieService authCookieService,
                               @Value("${app.security.trusted-proxies:}") String trustedProxies) {
        this.audit = audit;
        this.authCookieService = authCookieService;
        this.clientIpResolver = new ClientIpResolver(trustedProxies);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }
        MDC.put(SecurityAuditLogger.CLIENT_MDC_KEY, audit.digestClientIp(clientIpResolver.resolve(request)));
        try {
            filterChain.doFilter(request, response);
            recordIfNoteworthy(request, response);
        } catch (RuntimeException | IOException | ServletException ex) {
            // 异常也要留痕:此时状态码尚未写回,按 500 记一条失败事件后原样抛出。
            recordIfNoteworthy(request, response);
            throw ex;
        } finally {
            MDC.remove(SecurityAuditLogger.CLIENT_MDC_KEY);
        }
    }

    private void recordIfNoteworthy(HttpServletRequest request, HttpServletResponse response) {
        String uri = request.getRequestURI();
        if (SELF_AUDITED.contains(uri) || uri.startsWith("/api/webhooks/")) {
            return;
        }
        String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase(java.util.Locale.ROOT);
        int status = response.getStatus();
        CurrentUser actor = request.getAttribute(SecurityAuditLogger.ACTOR_ATTRIBUTE) instanceof CurrentUser user
                ? user : null;

        if (status == 403 || (status == 401 && hasCredential(request))) {
            audit.recordFor(actor == null ? null : actor.userId(), actor == null ? null : actor.username(),
                    "ACCESS_DENIED", Outcome.DENIED, "endpoint", method + " " + uri, "HTTP_" + status);
            return;
        }
        if (status == 429) {
            audit.recordFor(actor == null ? null : actor.userId(), actor == null ? null : actor.username(),
                    "RATE_LIMITED", Outcome.DENIED, "endpoint", method + " " + uri, "HTTP_429");
            return;
        }
        if (!WRITE_METHODS.contains(method)) {
            return; // 读请求不入审计流,否则正常浏览就能把它冲掉
        }
        audit.recordFor(actor == null ? null : actor.userId(), actor == null ? null : actor.username(),
                "API_" + method, status < 400 ? Outcome.SUCCESS : Outcome.FAILURE,
                "endpoint", uri, status < 400 ? null : "HTTP_" + status);
    }

    /** 只有「出示了凭据却被拒」才值得记一条 401,匿名探测不记。 */
    private boolean hasCredential(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && !header.isBlank()) {
            return true;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (authCookieService.getCookieName().equals(cookie.getName())) {
                return true;
            }
        }
        return false;
    }
}
