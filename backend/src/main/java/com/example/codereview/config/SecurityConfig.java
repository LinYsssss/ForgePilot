package com.example.codereview.config;

import com.example.codereview.auth.TokenAuthenticationFilter;
import com.example.codereview.common.security.SpaCsrfTokenRequestHandler;
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
            @Value("${app.ratelimit.client-errors-limit:10}") int clientErrorsRateLimit,
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
                    // 前端错误上报匿名可达:JS 报错大多发生在登录前/会话失效后,加认证门槛恰好
                    // 挡掉最需要的样本。滥用面由专用限流预算(client-errors-limit)+4KB 截断+不入库收口。
                    auth.requestMatchers("/api/client-errors").permitAll();
                    // 反馈导出是平台级语料工件(r8 回灌输入,含全部项目的反馈),按平台管理员
                    // 整体把关,与 SCM onboarding 同模式;服务层不再重复判角色。
                    auth.requestMatchers("/api/feedback/export").hasRole("ADMIN");
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
                                clientErrorsRateLimit, objectMapper, new ClientIpResolver(trustedProxies)),
                        TokenAuthenticationFilter.class
                );
        if (csrfEnabled) {
            // SPA 模式:令牌走非 HttpOnly Cookie 下发、请求头回传(见 SpaCsrfTokenRequestHandler)。
            // Webhook 免除:它来自 GitHub/GitLab,不可能带我们的 CSRF 令牌,其真实性由每个
            // installation 的 HMAC/令牌验签保证——免 CSRF 不等于免验签。
            // 错误上报免除:前端用 sendBeacon 上报(页面卸载时也能发出),它无法携带自定义头,
            // 而该端点匿名可达且不改任何服务端状态(只落日志),CSRF 在此没有可保护的对象。
            http.csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                    // 无状态会话下 SessionManagementFilter 把每个携带令牌的请求都当作"新认证",
                    // 默认策略(CsrfAuthenticationStrategy)会在每个已认证响应里清除 XSRF Cookie
                    // 并延迟重发——而普通 JSON 接口从不渲染令牌,浏览器的令牌就被永久抹掉,
                    // 登录后的第一个写请求即被拒。登录/退出的轮换由 CsrfTokenRotator 显式完成,
                    // 这里必须换成空策略,否则 SPA 的 CSRF 流程在真实浏览器里根本走不通。
                    .sessionAuthenticationStrategy(
                            new org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy())
                    .ignoringRequestMatchers("/api/webhooks/**", "/api/client-errors"));
        } else {
            // 关闭路径:部署侧经 SECURITY_CSRF_ENABLED=false 显式回退,或测试经
            // surefire 统一置 false(存量测试不带 token,见 backend/pom.xml)。
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
