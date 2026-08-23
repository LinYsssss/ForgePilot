package com.forgepilot.auth;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 强制执行 {@code user_account.session_version}。该值在会话建立时被捕获进
 * HttpSession，此后每个请求都与库中存储的值比对，因此一次改密会杀掉除
 * 执行改密的那个会话之外的所有会话（design.md 7、D013.7）。
 *
 * <p>这个比对是每个已认证请求一次主键读。这里**有意**不做缓存：过期的缓存会让
 * 已被吊销的会话继续存活，而那恰恰是本过滤器存在的唯一目的所要防止的。
 */
final class SessionVersionFilter extends OncePerRequestFilter {

    static final String SESSION_VERSION = SessionVersionFilter.class.getName() + ".SESSION_VERSION";

    private final UserAccountRepository accounts;
    private final AuthenticationEntryPoint unauthenticated;

    SessionVersionFilter(UserAccountRepository accounts, AuthenticationEntryPoint unauthenticated) {
        this.accounts = accounts;
        this.unauthenticated = unauthenticated;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Object captured = (session == null) ? null : session.getAttribute(SESSION_VERSION);
        if (captured instanceof Integer version && !stillCurrent(version)) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            this.unauthenticated.commence(request, response,
                    new CredentialsExpiredException("The session is no longer valid."));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean stillCurrent(int captured) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getPrincipal() instanceof AccountPrincipal principal
                && this.accounts.findById(principal.getUserId())
                        .filter(account -> account.getSessionVersion() == captured)
                        .isPresent();
    }
}
