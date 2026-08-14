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

    /** 错误上报与登录、普通流量三个桶互不侵占:任何一个被打满都不能拖垮另外两个。 */
    @Test
    void clientErrorReportsHaveTheirOwnBudgetSeparateFromLoginAndGeneralTraffic() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 120, 60, 8, 5, objectMapper,
                new ClientIpResolver(""));

        assertThat(passes(filter, "/api/client-errors", "203.0.113.20", 12)).isEqualTo(5);
        assertThat(passes(filter, "/api/projects", "203.0.113.20", 30)).isEqualTo(30);
        assertThat(passes(filter, "/api/auth/login", "203.0.113.20", 12)).isEqualTo(8);
    }

    /** 旧构造器必须回落到与 app-boundary.yml 相同的默认额度(10/窗口),不许静默为 0 把端点焊死。 */
    @Test
    void legacyConstructorsDefaultTheClientErrorBudgetToTen() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 120, 60, 8, objectMapper);

        assertThat(passes(filter, "/api/client-errors", "203.0.113.21", 15)).isEqualTo(10);
    }

    @Test
    void limitsAreTrackedPerClientIp() throws Exception {
        // MockHttpServletRequest 的 remoteAddr 是 127.0.0.1;把它列为受信代理,
        // X-Forwarded-For 才会被采信,从而模拟"经 nginx 转发的不同客户端"。
        RateLimitFilter filter = new RateLimitFilter(true, 120, 60, 3, objectMapper,
                new ClientIpResolver("127.0.0.1"));

        assertThat(passes(filter, "/api/auth/login", "198.51.100.1", 5)).isEqualTo(3);
        // 另一个 IP 有自己的额度,不被前一个连累
        assertThat(passes(filter, "/api/auth/login", "198.51.100.2", 5)).isEqualTo(3);
    }

    @Test
    void spoofedForwardedHeaderFromUntrustedPeerCannotEscapeTheLimit() throws Exception {
        // 不配受信代理:客户端每次换一个假 X-Forwarded-For 也必须落在同一个桶里
        RateLimitFilter filter = new RateLimitFilter(true, 120, 60, 3, objectMapper);

        int passed = 0;
        for (int i = 0; i < 10; i++) {
            passed += passes(filter, "/api/auth/login", "203.0.113." + i, 1);
        }
        assertThat(passed).isEqualTo(3);
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
