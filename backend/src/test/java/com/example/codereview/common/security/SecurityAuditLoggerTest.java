package com.example.codereview.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.codereview.common.security.SecurityAuditLogger.Outcome;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityAuditLoggerTest {

    private final SecurityAuditLogger audit = new SecurityAuditLogger("audit-salt-for-tests");
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger auditLogger;

    @BeforeEach
    void attachAppender() {
        auditLogger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("security.audit");
        appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        auditLogger.detachAppender(appender);
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @AfterAll
    static void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private List<ILoggingEvent> events() {
        return appender.list;
    }

    @Test
    void writesOneStructuredLineWithTheFixedFieldSet() {
        MDC.put("traceId", "trace-1234");
        MDC.put(SecurityAuditLogger.CLIENT_MDC_KEY, "abcdef012345");

        audit.recordFor(7L, "alice", "LOGIN", Outcome.SUCCESS, "user", "alice", null);

        assertThat(events()).hasSize(1);
        ILoggingEvent event = events().get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .isEqualTo("event=security_audit action=LOGIN outcome=SUCCESS actor=alice actorId=7"
                        + " resource=user:alice client=abcdef012345 trace=trace-1234 reason=-");
    }

    @Test
    void failuresAndDenialsAreWarnSoTheySurviveTheDefaultLevel() {
        audit.recordFor(null, "mallory", "LOGIN", Outcome.FAILURE, "user", "mallory", "UNKNOWN_USER");
        audit.recordFor(3L, "bob", "ACCESS_DENIED", Outcome.DENIED, "endpoint", "DELETE /api/projects/9", "HTTP_403");

        assertThat(events()).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.WARN);
        assertThat(events().get(0).getFormattedMessage()).contains("outcome=FAILURE", "reason=UNKNOWN_USER", "actorId=-");
        assertThat(events().get(1).getFormattedMessage()).contains("resource=endpoint:DELETE_/api/projects/9");
    }

    /** 用户名是攻击者可控的:换行会让一条记录变成两条,伪造出「登录成功」。 */
    @Test
    void sanitizesFieldsSoAnAttackerCannotForgeASecondRecord() {
        audit.recordFor(null, "eve\nevent=security_audit action=LOGIN outcome=SUCCESS", "LOGIN",
                Outcome.FAILURE, "user", "x", "BAD_CREDENTIALS");

        String line = events().get(0).getFormattedMessage();
        assertThat(line.lines()).hasSize(1);
        assertThat(line).doesNotContain("outcome=SUCCESS");
        assertThat(line).contains("outcome=FAILURE");
    }

    @Test
    void capsOverlongValues() {
        audit.recordFor(null, "a".repeat(500), "LOGIN", Outcome.FAILURE, "user", "b".repeat(500), "R");

        String line = events().get(0).getFormattedMessage();
        assertThat(line.length()).isLessThan(500);
    }

    @Test
    void takesTheActorFromTheSecurityContextWhenNotGivenOne() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(42L, "carol", "ADMIN"), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        audit.success("SCM_INSTALLATION_UPSERT", "scmInstallation", "5");

        assertThat(events().get(0).getFormattedMessage())
                .contains("actor=carol", "actorId=42", "resource=scmInstallation:5", "outcome=SUCCESS");
    }

    @Test
    void anonymousCallerIsRecordedAsPlaceholderRatherThanFailing() {
        audit.denied("ACCESS_DENIED", "endpoint", "POST /api/projects", "HTTP_401");

        assertThat(events().get(0).getFormattedMessage()).contains("actor=-", "actorId=-", "client=-", "trace=-");
    }

    /** 摘要要能关联同一来源,又不能把原始 IP 留在日志里。 */
    @Test
    void clientIpIsDigestedStablyAndIrreversibly() {
        String first = audit.digestClientIp("203.0.113.9");
        assertThat(first).isEqualTo(audit.digestClientIp("203.0.113.9"))
                .hasSize(12)
                .matches("[0-9a-f]{12}")
                .isNotEqualTo(audit.digestClientIp("203.0.113.10"));
        assertThat(first).doesNotContain("203");

        // 不同部署(不同密钥)对同一 IP 得到不同摘要,避免跨部署反查
        assertThat(new SecurityAuditLogger("another-salt").digestClientIp("203.0.113.9")).isNotEqualTo(first);
        assertThat(audit.digestClientIp(null)).isEqualTo("-");
        assertThat(audit.digestClientIp("  ")).isEqualTo("-");
    }
}
