package com.example.codereview.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.codereview.common.security.CurrentUser;
import com.example.codereview.common.security.SecurityAuditLogger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityAuditFilterTest {

    private static final String COOKIE_NAME = "reposage_auth";

    private final SecurityAuditLogger audit = new SecurityAuditLogger("audit-salt-for-tests");
    private SecurityAuditFilter filter;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger auditLogger;

    @BeforeEach
    void setUp() {
        filter = new SecurityAuditFilter(audit, COOKIE_NAME, "127.0.0.1");
        auditLogger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("security.audit");
        appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(appender);
    }

    private List<String> lines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private MockHttpServletResponse run(String method, String uri, int status, CurrentUser actor, Cookie cookie,
                                        String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        if (actor != null) {
            request.setAttribute(SecurityAuditLogger.ACTOR_ATTRIBUTE, actor);
        }
        if (cookie != null) {
            request.setCookies(cookie);
        }
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(status);
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void recordsWritesWithTheActorAndTheEndpoint() throws Exception {
        run("POST", "/api/projects/9/repository", 200, new CurrentUser(4L, "alice", "DEVELOPER"), null, null);

        assertThat(lines()).hasSize(1);
        assertThat(lines().get(0)).contains("action=API_POST", "outcome=SUCCESS", "actor=alice", "actorId=4",
                "resource=endpoint:/api/projects/9/repository");
        // 客户端 IP 只以摘要出现
        assertThat(lines().get(0)).doesNotContain("127.0.0.1");
    }

    @Test
    void marksFailedWritesAndKeepsTheStatus() throws Exception {
        run("DELETE", "/api/projects/9", 500, new CurrentUser(4L, "alice", "DEVELOPER"), null, null);

        assertThat(lines().get(0)).contains("action=API_DELETE", "outcome=FAILURE", "reason=HTTP_500");
    }

    @Test
    void readRequestsAreNotAudited() throws Exception {
        run("GET", "/api/projects", 200, new CurrentUser(4L, "alice", "DEVELOPER"), null, null);

        assertThat(lines()).isEmpty();
    }

    @Test
    void recordsForbiddenAsAnObjectLevelDenial() throws Exception {
        run("GET", "/api/projects/9/reviews", 403, new CurrentUser(4L, "alice", "DEVELOPER"), null, null);

        assertThat(lines().get(0)).contains("action=ACCESS_DENIED", "outcome=DENIED",
                "resource=endpoint:GET_/api/projects/9/reviews", "reason=HTTP_403", "actor=alice");
    }

    /** 未登录时打开页面就会产生一次 401,记下来只会淹没真正的信号。 */
    @Test
    void anonymousUnauthorizedProbeIsNotAuditedButARejectedCredentialIs() throws Exception {
        run("GET", "/api/auth/me", 401, null, null, null);
        assertThat(lines()).isEmpty();

        run("GET", "/api/auth/me", 401, null, new Cookie(COOKIE_NAME, "expired-token"), null);
        assertThat(lines()).hasSize(1);
        assertThat(lines().get(0)).contains("action=ACCESS_DENIED", "reason=HTTP_401");

        run("GET", "/api/projects", 401, null, null, "Bearer forged");
        assertThat(lines()).hasSize(2);
    }

    @Test
    void recordsRateLimiting() throws Exception {
        run("POST", "/api/reviews", 429, new CurrentUser(4L, "alice", "DEVELOPER"), null, null);

        assertThat(lines().get(0)).contains("action=RATE_LIMITED", "outcome=DENIED", "reason=HTTP_429");
    }

    /** 登录/退出与 webhook 各有更精确的事件,兜底过滤器不再记一遍。 */
    @Test
    void skipsPathsThatAuditThemselves() throws Exception {
        run("POST", "/api/auth/login", 200, null, null, null);
        run("POST", "/api/auth/logout", 200, new CurrentUser(4L, "alice", "DEVELOPER"), null, null);
        run("POST", "/api/webhooks/scm/github", 202, null, null, null);

        assertThat(lines()).isEmpty();
    }

    @Test
    void ignoresNonApiTraffic() throws Exception {
        run("POST", "/actuator/refresh", 200, null, null, null);

        assertThat(lines()).isEmpty();
    }

    @Test
    void auditsWhenTheChainThrowsInsteadOfSwallowingTheEvent() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/projects");
        request.setRequestURI("/api/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> {
            throw new IllegalStateException("boom");
        };

        try {
            filter.doFilter(request, response, chain);
        } catch (Exception expected) {
            // 原样抛出,不吞异常
        }
        assertThat(lines()).hasSize(1);
        assertThat(lines().get(0)).contains("action=API_POST");
    }
}
