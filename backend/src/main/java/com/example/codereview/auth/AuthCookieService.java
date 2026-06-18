package com.example.codereview.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthCookieService {

    private final String cookieName;
    private final boolean secure;
    private final long ttlSeconds;
    private final String sameSite;

    public AuthCookieService(
            @Value("${app.security.auth-cookie-name:reposage_auth}") String cookieName,
            @Value("${app.security.auth-cookie-secure:false}") boolean secure,
            @Value("${app.security.token-ttl-seconds}") long ttlSeconds,
            @Value("${app.security.auth-cookie-same-site:Lax}") String sameSite
    ) {
        this.cookieName = cookieName;
        this.secure = secure;
        this.ttlSeconds = ttlSeconds;
        this.sameSite = sameSite;
    }

    public void writeLoginCookie(String token, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie(token, ttlSeconds).toString());
    }

    public void clearCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("", 0).toString());
    }

    public String getCookieName() {
        return cookieName;
    }

    private ResponseCookie buildCookie(String value, long maxAge) {
        return ResponseCookie.from(cookieName, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
