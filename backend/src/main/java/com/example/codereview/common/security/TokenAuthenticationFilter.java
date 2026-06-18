package com.example.codereview.common.security;

import com.example.codereview.auth.AuthCookieService;
import com.example.codereview.auth.TokenService;
import com.example.codereview.auth.TokenService.ParsedToken;
import com.example.codereview.auth.UserAccount;
import com.example.codereview.auth.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private final TokenService tokenService;
    private final UserAccountRepository users;
    private final AuthCookieService authCookieService;

    public TokenAuthenticationFilter(TokenService tokenService, UserAccountRepository users, AuthCookieService authCookieService) {
        this.tokenService = tokenService;
        this.users = users;
        this.authCookieService = authCookieService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && !token.isBlank()) {
            ParsedToken parsedToken = tokenService.parseClaims(token);
            UserAccount user = resolveActiveUser(parsedToken);
            if (parsedToken != null && user != null) {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        parsedToken.toCurrentUser(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()))
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (authCookieService.getCookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private UserAccount resolveActiveUser(ParsedToken parsedToken) {
        if (parsedToken == null) {
            return null;
        }
        return users.findById(parsedToken.userId())
                .filter(UserAccount::isEnabled)
                .filter(user -> user.getSessionVersion() == parsedToken.sessionVersion())
                .orElse(null);
    }
}
