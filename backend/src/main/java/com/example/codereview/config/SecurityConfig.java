package com.example.codereview.config;

import com.example.codereview.common.security.TokenAuthenticationFilter;
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
            @Value("${app.ratelimit.window-seconds:60}") int rateLimitWindowSeconds
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/api/auth/login", "/api/actuator/health", "/actuator/health").permitAll();
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
                        new RateLimitFilter(rateLimitEnabled, rateLimit, rateLimitWindowSeconds, objectMapper),
                        TokenAuthenticationFilter.class
                );
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
