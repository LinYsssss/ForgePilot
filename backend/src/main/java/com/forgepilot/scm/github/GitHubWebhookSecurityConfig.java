package com.forgepilot.scm.github;

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
 * The webhook endpoint's own filter chain.
 *
 * <p>The application chain ends in {@code anyRequest().authenticated()} with
 * cookie CSRF, and a GitHub delivery carries neither a session nor an
 * {@code X-XSRF-TOKEN} header, so without this it would be refused before the
 * signature was ever checked. This chain is ordered ahead of that one and matches
 * exactly one path.
 *
 * <p>Nothing is weakened by it: the HMAC over the raw body <em>is</em> this
 * endpoint's authentication, and it is stronger than a session would be. CSRF has
 * nothing to protect here either — there is no ambient credential for a browser to
 * be tricked into replaying, and no session is created.
 */
@Configuration
@ConditionalOnWebApplication(type = Type.SERVLET)
class GitHubWebhookSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain gitHubWebhookFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(GitHubWebhookController.PATH)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(CsrfConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(RequestCacheConfigurer::disable)
                .build();
    }
}
