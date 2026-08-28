package com.forgepilot.scm;

import java.time.Clock;

import com.forgepilot.common.RateLimitFilter;
import com.forgepilot.common.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * 两个 provider 的 webhook 端点各自对**原始请求字节**做认证，
 * 因此不使用浏览器会话，也不使用 Cookie CSRF token。
 */
@Configuration
@ConditionalOnWebApplication(type = Type.SERVLET)
class ScmWebhookSecurityConfig {

    static final String GITHUB_PATH = "/api/scm/github/webhook";
    static final String GITLAB_PATH = "/api/scm/gitlab/webhook";

    private static final int MAX_TRACKED_CLIENTS = 10_000;

    /**
     * 这里的配额<strong>刻意宽松</strong>，因为限得太紧就是丢投递：
     * 一次 push 可能连带几个事件，而 provider 的重试也会落在同一个地址上。
     *
     * <p>它防的不是伪造——签名校验负责那个。它防的是<strong>未认证请求消耗资源</strong>：
     * 每次投递在验签之前就要解析 JSON 并查库定位仓库，验签之后还要发起一次出站的
     * 权威读取。洪泛因此是有放大效应的。
     */
    @Bean
    @Order(1)
    SecurityFilterChain scmWebhookFilterChain(HttpSecurity http, ObjectMapper json,
            @Value("${forgepilot.security.webhook-deliveries-per-minute:60}") int permits)
            throws Exception {
        RateLimitFilter deliveryLimit = new RateLimitFilter("webhook",
                new RateLimiter(Clock.systemUTC(), permits, 60_000L, MAX_TRACKED_CLIENTS),
                json, (method, path) -> true);
        return http
                .securityMatcher(GITHUB_PATH, GITLAB_PATH)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(CsrfConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(RequestCacheConfigurer::disable)
                // 本链已由 securityMatcher 限定到两个 webhook 路径，
                // 因此这里的判定恒为真，不必再比对路径。
                .addFilterBefore(deliveryLimit, AuthorizationFilter.class)
                .build();
    }
}
