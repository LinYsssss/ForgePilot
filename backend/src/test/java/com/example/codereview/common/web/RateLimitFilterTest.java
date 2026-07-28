package com.example.codereview.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockHttpServletRequest request(String uri, String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRequestURI(uri);
        req.addHeader("X-Forwarded-For", ip);
        return req;
    }

    /** 打 n 次同一路径,返回真正被放行(进入下游)的次数。 */
    private int passes(RateLimitFilter filter, String uri, String ip, int attempts) throws Exception {
        AtomicInteger passed = new AtomicInteger();
        FilterChain chain = (rq, rs) -> passed.incrementAndGet();
        for (int i = 0; i < attempts; i++) {
            filter.doFilter(request(uri, ip), new MockHttpServletResponse(), chain);
        }
        return passed.get();
    }

    @Test
    void loginHasItsOwnTighterBudgetThanGeneralApiTraffic() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 120, 60, 8, objectMapper);

        // 同一 IP 连打登录:只有前 8 次放行
        assertThat(passes(filter, "/api/auth/login", "203.0.113.7", 20)).isEqualTo(8);
        // 登录被限不影响同 IP 的普通接口(独立计数)
        assertThat(passes(filter, "/api/projects", "203.0.113.7", 30)).isEqualTo(30);
    }

    @Test
    void limitsAreTrackedPerClientIp() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 120, 60, 3, objectMapper);

        assertThat(passes(filter, "/api/auth/login", "198.51.100.1", 5)).isEqualTo(3);
        // 另一个 IP 有自己的额度,不被前一个连累
        assertThat(passes(filter, "/api/auth/login", "198.51.100.2", 5)).isEqualTo(3);
    }

    @Test
    void throttledLoginAnswers429WithRetryAfter() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 120, 60, 1, objectMapper);
        FilterChain chain = (rq, rs) -> { };

        filter.doFilter(request("/api/auth/login", "192.0.2.5"), new MockHttpServletResponse(), chain);
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(request("/api/auth/login", "192.0.2.5"), blocked, chain);

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotNull();
    }

    @Test
    void nonApiPathsAndDisabledFilterAreNeverThrottled() throws Exception {
        assertThat(passes(new RateLimitFilter(true, 120, 60, 1, objectMapper), "/actuator/health", "192.0.2.9", 10))
                .isEqualTo(10);
        assertThat(passes(new RateLimitFilter(false, 120, 60, 1, objectMapper), "/api/auth/login", "192.0.2.9", 10))
                .isEqualTo(10);
    }
}
