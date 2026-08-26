package com.forgepilot.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.forgepilot.common.ApiError;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * 基于服务端进程内 {@code HttpSession} 的表单登录，配合 Cookie 形式的 CSRF。
 * 安全过滤器链要回答的一切都写在这里：它的 401 与 403 是在 Spring MVC 运行之前
 * 产生的，因此永远不会到达 {@code ApiExceptionHandler}——若不在此统一处理，
 * 它们就会逃出 ARCHITECTURE.md 2.4 规定的唯一错误体结构。
 */
@Configuration
class SecurityConfig {

    /**
     * 这些响应体的 {@code traceId} 为空，因为这里不写任何日志：
     * {@code ApiExceptionHandler} 只在它同时记录原因的地方才生成 traceId；
     * 而逐响应生成一个值还会让两种登录失败在字节层面变得可区分，
     * 这是 API.md 明令禁止的。
     */
    private static final ApiError UNAUTHENTICATED =
            new ApiError("unauthorized", "Authentication is required.", "");
    private static final ApiError BAD_CREDENTIALS =
            new ApiError("unauthorized", "Invalid username or password.", "");
    /** 访问被拒的唯一来源就是缺失或错误的 CSRF token。 */
    private static final ApiError FORBIDDEN =
            new ApiError("forbidden", "The request was rejected.", "");

    /** 口令哈希使用 Spring Security 默认的 BCrypt。 */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 只有 servlet 应用才有过滤器链，而数据库测试跑在共享同一份
     * 组件扫描的非 Web 上下文里。
     */
    @Bean
    @ConditionalOnWebApplication(type = Type.SERVLET)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, UserAccountRepository accounts,
            ObjectMapper json) throws Exception {
        // SessionVersionFilter 也复用它，使「会话已被吊销」与「压根没有会话」
        // 返回完全相同的响应体。
        AuthenticationEntryPoint unauthenticated = (request, response, exception) ->
                write(response, json, HttpStatus.UNAUTHORIZED, UNAUTHENTICATED);

        return http
                .authorizeHttpRequests(requests -> requests
                        // 404 或 500 是通过容器的 ERROR dispatch 转到 /error 才送达客户端的。
                        // 实测：没有这一行时，该 dispatch 会被当作一个匿名请求重新鉴权，
                        // 于是本来允许访问的路径上的 404 会被答成 401。客户端无法借此
                        // 探测什么：下面的 entry point 直接设置状态码而不调用 sendError，
                        // 因此被拒绝的请求本身不会产生 ERROR dispatch。
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                        // application.yml 只暴露了 `health`，因此放行整个命名空间
                        // 并不会多暴露任何东西；ActuatorExposureTest 与
                        // scripts/phase1-compose-smoke.sh 要求 /actuator/metrics 必须
                        // 返回 404，正是这条保证的守门人。
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                // Spring Security 7.1 自带的 SPA 接线：Cookie 仓库 + 一个接受
                // 「原始 cookie 值经 header 回显」并提前解析 token 的请求处理器，
                // 使每个响应都带上 XSRF-TOKEN。没有它，延迟生成的 token 永远不会写出，
                // XOR 处理器也会拒绝原始 cookie 值。
                .csrf(CsrfConfigurer::spa)
                .formLogin(login -> login
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> {
                            AccountPrincipal principal = (AccountPrincipal) authentication.getPrincipal();
                            request.getSession().setAttribute(
                                    SessionVersionFilter.SESSION_VERSION, principal.getSessionVersion());
                            write(response, json, HttpStatus.OK,
                                    new AccountResponse(principal.getUserId(), principal.getUsername(),
                                            principal.getDisplayName()));
                        })
                        // 所有失败共用同一个响应体，使调用方无法区分
                        // 用户名不存在、口令错误和账号被禁用。
                        .failureHandler((request, response, exception) ->
                                write(response, json, HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS)))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value())))
                // 显式设置这两项，同时也阻止 Spring Security 生成它自带的
                // HTML 登录页与登出页——这个 JSON API 用不到它们。
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler((request, response, exception) ->
                                write(response, json, HttpStatus.FORBIDDEN, FORBIDDEN)))
                // 登录后不重放任何请求，因此匿名请求无需占用 session。
                .requestCache(RequestCacheConfigurer::disable)
                .addFilterBefore(new SessionVersionFilter(accounts, unauthenticated), AuthorizationFilter.class)
                .build();
    }

    private static void write(HttpServletResponse response, ObjectMapper json, HttpStatus status,
            Object body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(body));
    }
}
