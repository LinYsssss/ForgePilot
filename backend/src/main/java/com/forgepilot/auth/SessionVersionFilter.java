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
 * Enforces {@code user_account.session_version}. The value is captured into the
 * HttpSession when the session is established and compared with the stored one on
 * every later request, so a password change kills every session except the one
 * that performed it (design.md 7, D013.7).
 *
 * <p>The comparison is a primary-key read per authenticated request. There is
 * deliberately no cache: a stale cache would keep a revoked session alive, which
 * is the one thing this filter exists to prevent.
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
