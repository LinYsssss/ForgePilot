package com.forgepilot.scm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 两个 provider 的 webhook 端点各自对**原始请求字节**做认证，
 * 因此不使用浏览器会话，也不使用 Cookie CSRF token。
 */
@Configuration
@ConditionalOnWebApplication(type = Type.SERVLET)
class ScmWebhookSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain scmWebhookFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/scm/github/webhook", "/api/scm/gitlab/webhook")
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(CsrfConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(RequestCacheConfigurer::disable)
                .build();
    }
}
