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
 * Form login over a server-side, in-process {@code HttpSession} with cookie-based
 * CSRF (D013.7). Everything the security filter chain answers is written here:
 * its 401 and 403 are produced before Spring MVC runs and therefore never reach
 * {@code ApiExceptionHandler}, so they would otherwise escape the single error
 * body shape of ARCHITECTURE.md 2.4.
 */
@Configuration
class SecurityConfig {

    /**
     * These bodies carry an empty {@code traceId} because nothing is logged here:
     * {@code ApiExceptionHandler} mints one only where it also logs the cause, and
     * a per-response value would additionally make the two login failure modes
     * distinguishable byte for byte, which api-contract.md 1 forbids.
     */
    private static final ApiError UNAUTHENTICATED =
            new ApiError("unauthorized", "Authentication is required.", "");
    private static final ApiError BAD_CREDENTIALS =
            new ApiError("unauthorized", "Invalid username or password.", "");
    /** In batch 1 the only source of an access denial is a missing or wrong CSRF token. */
    private static final ApiError FORBIDDEN =
            new ApiError("forbidden", "The request was rejected.", "");

    /** D013.7: password hashes are Spring Security's default BCrypt. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Only a servlet application has a filter chain, while the batch 1 database
     * tests run in a non-web context that shares this component scan.
     */
    @Bean
    @ConditionalOnWebApplication(type = Type.SERVLET)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, UserAccountRepository accounts,
            ObjectMapper json) throws Exception {
        // Also used by SessionVersionFilter, so a revoked session and an absent one
        // are answered with the same body.
        AuthenticationEntryPoint unauthenticated = (request, response, exception) ->
                write(response, json, HttpStatus.UNAUTHORIZED, UNAUTHENTICATED);

        return http
                .authorizeHttpRequests(requests -> requests
                        // A 404 or 500 reaches the client through a container ERROR dispatch
                        // to /error. Measured: without this the dispatch is authorized afresh
                        // as an anonymous request to /error, so a 404 on a permitted path is
                        // answered 401. A client cannot reach this: the entry point below sets
                        // the status directly instead of calling sendError, so a rejected
                        // request never produces an ERROR dispatch of its own.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                        // Only `health` is exposed (application.yml), so permitting the whole
                        // namespace exposes nothing else; the 404 that ActuatorExposureTest and
                        // scripts/phase1-compose-smoke.sh demand for /actuator/metrics is what
                        // keeps that honest.
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                // Spring Security 7.1's own SPA wiring: the cookie repository plus a request
                // handler that accepts the raw cookie value echoed in a header and resolves the
                // token eagerly, so every response carries XSRF-TOKEN. Without it the deferred
                // token is never written and the XOR handler rejects the raw cookie value.
                .csrf(CsrfConfigurer::spa)
                .formLogin(login -> login
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> {
                            AccountPrincipal principal = (AccountPrincipal) authentication.getPrincipal();
                            request.getSession().setAttribute(
                                    SessionVersionFilter.SESSION_VERSION, principal.getSessionVersion());
                            write(response, json, HttpStatus.OK,
                                    new AccountResponse(principal.getUserId(), principal.getUsername()));
                        })
                        // One body for every failure, so a caller cannot tell an unknown
                        // username from a wrong password or a disabled account.
                        .failureHandler((request, response, exception) ->
                                write(response, json, HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS)))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value())))
                // Setting these explicitly also stops Spring Security from generating its
                // HTML login and logout pages, which this JSON API has no use for.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler((request, response, exception) ->
                                write(response, json, HttpStatus.FORBIDDEN, FORBIDDEN)))
                // Nothing is replayed after login, so no anonymous request needs a session.
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
