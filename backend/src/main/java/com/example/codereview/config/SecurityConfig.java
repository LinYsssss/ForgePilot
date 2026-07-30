package com.example.codereview.config;

import com.example.codereview.common.security.SpaCsrfTokenRequestHandler;
import com.example.codereview.common.security.TokenAuthenticationFilter;
import com.example.codereview.common.web.ClientIpResolver;
import com.example.codereview.common.web.RateLimitFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            TokenAuthenticationFilter tokenFilter,
            ObjectMapper objectMapper,
            @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled,
            @Value("${app.ratelimit.enabled:true}") boolean rateLimitEnabled,
            @Value("${app.ratelimit.limit:120}") int rateLimit,
            @Value("${app.ratelimit.window-seconds:60}") int rateLimitWindowSeconds,
            @Value("${app.ratelimit.login-limit:8}") int loginRateLimit,
            @Value("${app.security.trusted-proxies:}") String trustedProxies,
            @Value("${app.security.csrf.enabled:false}") boolean csrfEnabled
    ) throws Exception {
        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/api/auth/login", "/api/auth/csrf",
                            "/api/actuator/health", "/actuator/health").permitAll();
                    // Prometheus 在容器网络内直连 backend:8080 抓取指标,自身不带鉴权令牌,故放行该端点。
                    // 公网侧由 nginx 只暴露 /actuator/health、其余 /actuator/* 返回 404,指标不外泄。
                    auth.requestMatchers("/actuator/prometheus").permitAll();
                    // SCM webhooks are unauthenticated at the bearer-token layer; each delivery is
                    // instead gated by per-installation HMAC/token verification in the controller.
                    auth.requestMatchers("/api/webhooks/**").permitAll();
                    // SCM installation onboarding 持有 webhook 验签密钥与回写凭据,仅管理员可操作。
                    auth.requestMatchers("/api/scm/installations/**").hasRole("ADMIN");
                    if (h2ConsoleEnabled) {
                        auth.requestMatchers("/h2-console/**").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(
                        new RateLimitFilter(rateLimitEnabled, rateLimit, rateLimitWindowSeconds, loginRateLimit,
                                objectMapper, new ClientIpResolver(trustedProxies)),
                        TokenAuthenticationFilter.class
                );
        if (csrfEnabled) {
            // SPA 模式:令牌走非 HttpOnly Cookie 下发、请求头回传(见 SpaCsrfTokenRequestHandler)。
            // Webhook 免除:它来自 GitHub/GitLab,不可能带我们的 CSRF 令牌,其真实性由每个
            // installation 的 HMAC/令牌验签保证——免 CSRF 不等于免验签。
            http.csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                    .ignoringRequestMatchers("/api/webhooks/**"));
        } else {
            // 默认关闭:开关是 docs/并行实施拆分方案.md 冻结的跨线契约,由合流阶段统一打开。
            http.csrf(AbstractHttpConfigurer::disable);
        }
        if (h2ConsoleEnabled) {
            http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        } else {
            http.headers(headers -> headers.frameOptions(frame -> frame.deny()));
        }
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
