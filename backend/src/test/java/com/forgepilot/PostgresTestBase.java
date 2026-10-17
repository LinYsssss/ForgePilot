package com.forgepilot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.http.Cookie;

import com.forgepilot.knowledge.KnowledgeIngestionProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 整轮测试共用**一个**真实的 PostgreSQL 15 + pgvector。容器由静态初始化块启动一次，
 * 并在 JVM 退出时由 Ryuk 回收，因此所有数据库测试共享它，
 * 而不是每个类都付一次冷启动的代价。
 *
 * <p>这里**刻意**没有「Docker 不存在就跳过」的分支：
 * 一个跑不起来的数据库测试必须让构建失败。
 */
public abstract class PostgresTestBase {

    private static final String IMAGE = "pgvector/pgvector:0.8.6-pg15-bookworm";

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("forgepilot")
            .withUsername("forgepilot")
            .withPassword("forgepilot")
            .withStartupTimeout(Duration.ofMinutes(3));

    static {
        POSTGRES.start();
    }

    /** Drain the durable queue explicitly in tests that need indexed fixtures. */
    protected static void processPendingKnowledge(KnowledgeIngestionProcessor processor, JdbcTemplate jdbc) {
        for (int remaining = 1000; remaining > 0; remaining--) {
            if (jdbc.queryForObject("select count(*) from knowledge_document where status = 'PENDING'",
                    Integer.class) == 0) return;
            processor.processNext();
        }
        throw new AssertionError("Knowledge ingestion did not drain the pending fixtures.");
    }

    /**
     * 应用的 CSRF 走浏览器实际走的那条路：同一个值既放在 cookie 里，也由 header 回显
     * （{@code CookieCsrfTokenRepository} 的双提交名 {@code XSRF-TOKEN} / {@code X-XSRF-TOKEN}）。
     *
     * <p>这些用例**刻意**不使用 {@code SecurityMockMvcRequestPostProcessors.csrf()}。
     * 那个后处理器会把过滤器链里 {@code CsrfFilter} 的 {@code CsrfTokenRepository} 换成
     * {@code TestCsrfTokenRepository}（包着一个 {@code HttpSessionCsrfTokenRepository}），
     * 于是 token 被写进 session 而不是响应 cookie。替换作用在**共享的**过滤器链上，
     * 而且在请求结束后仍留在那里，所以它污染同一 JVM 中之后执行的所有测试类：
     * 依赖 {@code GET /api/auth/me} 下发 {@code XSRF-TOKEN} 的用例会随类的执行顺序失败。
     * 走真实路径没有任何全局副作用。
     */
    protected static RequestPostProcessor spaCsrf() {
        return request -> {
            List<Cookie> cookies = new ArrayList<>();
            Cookie[] existing = request.getCookies();
            if (existing != null) {
                cookies.addAll(Arrays.asList(existing));
            }
            cookies.add(new Cookie(SPA_CSRF_COOKIE, SPA_CSRF_TOKEN));
            request.setCookies(cookies.toArray(new Cookie[0]));
            request.addHeader(SPA_CSRF_HEADER, SPA_CSRF_TOKEN);
            return request;
        };
    }

    /** 双提交 cookie 与 header 共用的值；服务端只比较两者是否一致。 */
    private static final String SPA_CSRF_TOKEN = "spa-csrf-token";

    private static final String SPA_CSRF_COOKIE = "XSRF-TOKEN";

    private static final String SPA_CSRF_HEADER = "X-XSRF-TOKEN";

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // 它在生产环境中没有任何兜底声明，因此缺了它上下文就起不来。
        // 在这里注入，而不是在 application.yml 里给默认值：一个默认值就是一把
        // 到处都能悄无声息生效的弱密钥，而这恰恰是「失败即关闭」所要防止的。
        // Compose 与 CI 各自注入它们同样是假的值。
        registry.add("forgepilot.scm.secret-key", () -> "test-only-not-a-real-key");
        // Tests drive pending ingestion explicitly, without a background worker racing their fixtures.
        registry.add("forgepilot.knowledge.ingestion-interval-ms", () -> "3600000");
    }
}
