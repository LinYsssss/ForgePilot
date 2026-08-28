package com.forgepilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.StringUtils;
import org.springframework.web.context.WebApplicationContext;

/**
 * The auth contract of API.md against a real PostgreSQL and the real
 * security filter chain. Every request goes through {@link Browser}, which behaves
 * like a JS client: it keeps its session, keeps the {@code XSRF-TOKEN} cookie and
 * echoes it in the header.
 */
// Measured: after some other web test classes run, this one receives a 401
// carrying no XSRF-TOKEN cookie at all, and every write it then attempts is
// refused. It passes alone and through Compose, so the SPA contract itself is
// intact; what is not intact is the assumption that a shared test context still
// issues the cookie after another class has used it. Rather than chase every
// class that could disturb it, this one asks for a context nobody has touched.
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@SpringBootTest
class AuthApiTest extends PostgresTestBase {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String NEW_PASSWORD = "rotated-horse-battery-staple";
    private static final String WRONG_PASSWORD = "guessed-horse-battery-staple";

    /** 一个 RFC 5737 文档用地址，只有限流那条测试用它：打满它不会影响本类其他测试。 */
    private static final String FLOODING_ADDRESS = "203.0.113.7";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** 本类专用的源地址：限流按源地址计数，各测试类分开才不会互相耗尽配额。 */
    private static final AtomicInteger ADDRESSES = new AtomicInteger();

    /** 从配置读而不是写死：配额调整时本测试跟着走而不是变红。 */
    @Value("${forgepilot.security.login-attempts-per-minute}")
    private int loginPermits;

    @Value("${forgepilot.security.registrations-per-ten-minutes}")
    private int registerPermits;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    /** Every response body this test produced, checked for leaked credentials afterwards. */
    private final List<String> bodies = new ArrayList<>();

    @BeforeEach
    void buildMockMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
    }

    @AfterEach
    void noResponseBodyCarriesACredential() {
        // Only the accounts this class created: another test class' placeholder hash
        // would say nothing about these responses.
        List<String> hashes = this.jdbc.queryForList(
                "select password_hash from user_account where username like 'auth-user-%'", String.class);
        assertThat(hashes).isNotEmpty();
        assertThat(this.bodies).isNotEmpty();
        assertThat(this.bodies).allSatisfy(body -> {
            assertThat(body).doesNotContain(PASSWORD, NEW_PASSWORD, WRONG_PASSWORD);
            assertThat(body).doesNotContain(hashes.toArray(String[]::new));
        });
    }

    @Test
    void loginEstablishesTheSessionThatMeReports() throws Exception {
        Browser browser = new Browser();
        browser.bootstrap();
        String username = register(browser);

        MockHttpServletResponse login = browser.login(username, PASSWORD);

        assertThat(login.getStatus()).isEqualTo(200);
        assertThat(login.getContentAsString()).isEqualTo(accountJson(username));
        assertThat(browser.me().getContentAsString()).isEqualTo(accountJson(username));
    }

    @Test
    void anUnknownUsernameAndAWrongPasswordFailIdentically() throws Exception {
        Browser browser = new Browser();
        browser.bootstrap();
        String username = register(browser);

        MockHttpServletResponse unknownUsername = browser.login("absent-" + username, PASSWORD);
        MockHttpServletResponse wrongPassword = browser.login(username, WRONG_PASSWORD);

        assertThat(unknownUsername.getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getStatus()).isEqualTo(unknownUsername.getStatus());
        assertThat(wrongPassword.getContentAsString()).isEqualTo(unknownUsername.getContentAsString());
    }

    @Test
    void logoutEndsTheSessionItWasSentWith() throws Exception {
        Browser browser = new Browser();
        browser.bootstrap();
        String username = register(browser);
        browser.login(username, PASSWORD);
        assertThat(browser.me().getStatus()).isEqualTo(200);

        assertThat(browser.logout().getStatus()).isEqualTo(204);

        assertThat(browser.me().getStatus()).isEqualTo(401);
    }

    @Test
    void changingThePasswordKeepsThisSessionAndEndsTheOthers() throws Exception {
        Browser first = new Browser();
        first.bootstrap();
        String username = register(first);
        first.login(username, PASSWORD);

        Browser second = new Browser();
        second.bootstrap();
        second.login(username, PASSWORD);
        assertThat(second.me().getStatus()).isEqualTo(200);

        assertThat(first.changePassword(WRONG_PASSWORD, NEW_PASSWORD).getStatus()).isEqualTo(422);
        assertThat(first.changePassword(PASSWORD, NEW_PASSWORD).getStatus()).isEqualTo(204);

        assertThat(first.me().getStatus()).isEqualTo(200);
        assertThat(second.me().getStatus()).isEqualTo(401);
    }

    @Test
    void changingTheDisplayNameSurvivesTheExistingSessionAndRefresh() throws Exception {
        Browser browser = new Browser();
        browser.bootstrap();
        String username = register(browser);
        browser.login(username, PASSWORD);
        Long id = jdbc.queryForObject("select id from user_account where username = ?", Long.class, username);
        String expected = "{\"id\":%d,\"username\":\"%s\",\"displayName\":\"New Name\"}"
                .formatted(id, username);

        assertThat(browser.changeDisplayName("New Name").getContentAsString()).isEqualTo(expected);
        assertThat(browser.me().getContentAsString()).isEqualTo(expected);
    }

    @Test
    void aWriteIsRejectedUntilTheCookieDerivedCsrfHeaderIsSent() throws Exception {
        Browser browser = new Browser();

        MockHttpServletResponse bootstrap = browser.bootstrap();
        assertThat(csrfCookieOf(bootstrap)).isNotEmpty();

        String username = nextUsername();
        assertThat(browser.sendWithoutCsrfHeader(registration(username)).getStatus()).isEqualTo(403);

        // The same username still registers, so the rejected request really did nothing.
        assertThat(browser.send(registration(username)).getStatus()).isEqualTo(201);
    }

    @Test
    void aDuplicateUsernameIsRejected() throws Exception {
        Browser browser = new Browser();
        browser.bootstrap();
        String username = register(browser);

        assertThat(browser.send(registration(username)).getStatus()).isEqualTo(409);
    }

    /**
     * 登录限流<strong>装在真实过滤器链里</strong>，并答那个唯一的错误体。
     * {@code RateLimiterTest} 证明计数逻辑，证明不了装配。
     *
     * <p>上界取 {@code 2 * 配额 + 1} 而非恰好 {@code 配额 + 1}：固定窗口的边界是绝对
     * 时刻，这串请求可能正好跨过它而使配额整份恢复。断言的是「一定会被限、且不会提前
     * 被限」，而不是一个会偶发翻车的精确次序。
     */
    @Test
    void repeatedLoginAttemptsFromOneAddressAreRateLimited() throws Exception {
        // 走完整的 SPA 路径，而不是裸发 POST：登录受 CSRF 保护，裸发会在
        // CsrfFilter 处以 403 结束，根本走不到限流器——那样这条测试只会
        // 一直绿着，却什么都没验证。
        Browser attacker = new Browser(FLOODING_ADDRESS);
        attacker.bootstrap();
        String username = register(attacker);

        MockHttpServletResponse limited = null;
        int limitedAt = 0;
        for (int attempt = 1; attempt <= 2 * loginPermits + 1 && limited == null; attempt++) {
            MockHttpServletResponse response = attacker.login(username, WRONG_PASSWORD);
            if (response.getStatus() == 429) {
                limited = response;
                limitedAt = attempt;
            } else {
                assertThat(response.getStatus())
                        .as("配额内第 %d 次应由凭据决定，而不是被限流", attempt)
                        .isEqualTo(401);
            }
        }

        assertThat(limited).as("发满两倍配额仍未被限流，说明过滤器没有生效").isNotNull();
        assertThat(limitedAt).as("被限的次序不可能早于配额本身").isGreaterThan(loginPermits);
        assertThat(limited.getContentAsString())
                .contains("\"code\":\"too_many_requests\"")
                .doesNotContain("password");
    }

    /**
     * 注册限流也必须真的生效。
     *
     * <p>登录那条测试绿着并不能推出这条也绿：两个限流器是<strong>同一个</strong>
     * {@code OncePerRequestFilter} 子类的两个实例装在同一条链上，而该基类默认按
     * <em>类名</em>做「本请求已过此过滤器」的标记，于是第二个实例会在每个请求上
     * 整个跳过自己。这种失效是静默的——注册���常 201，配额形同虚设。
     */
    @Test
    void repeatedRegistrationsFromOneAddressAreRateLimited() throws Exception {
        Browser flood = new Browser("203.0.113.9");
        flood.bootstrap();

        MockHttpServletResponse limited = null;
        for (int attempt = 1; attempt <= 2 * registerPermits + 1 && limited == null; attempt++) {
            MockHttpServletResponse response = flood.send(registration(nextUsername()));
            if (response.getStatus() == 429) {
                limited = response;
            } else {
                assertThat(response.getStatus())
                        .as("配额内第 %d 次应当成功", attempt).isEqualTo(201);
            }
        }

        assertThat(limited).as("发满两倍配额仍未被限流，说明这个过滤器没有生效").isNotNull();
        assertThat(limited.getContentAsString()).contains("\"code\":\"too_many_requests\"");
    }

    // ------------------------------------------------------------------ helpers

    private String register(Browser browser) throws Exception {
        String username = nextUsername();
        assertThat(browser.send(registration(username)).getStatus()).isEqualTo(201);
        return username;
    }

    private static String nextUsername() {
        return "auth-user-" + SEQUENCE.incrementAndGet();
    }

    private static MockHttpServletRequestBuilder registration(String username) {
        return post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"displayName\":\"Test User\",\"password\":\"%s\"}"
                        .formatted(username, PASSWORD));
    }

    /** The exact body API.md promises for register, login and me. */
    private String accountJson(String username) {
        Long id = this.jdbc.queryForObject(
                "select id from user_account where username = ?", Long.class, username);
        return "{\"id\":%d,\"username\":\"%s\",\"displayName\":\"Test User\"}"
                .formatted(id, username);
    }

    private static String csrfCookieOf(MockHttpServletResponse response) {
        String value = "";
        for (Cookie cookie : response.getCookies()) {
            // A login rotates the token by writing a deletion first, so the last
            // non-empty XSRF-TOKEN is the one a browser would keep.
            if (CSRF_COOKIE.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                value = cookie.getValue();
            }
        }
        return value;
    }

    /** One client: its own HttpSession and its own XSRF-TOKEN cookie. */
    private final class Browser {

        private final MockHttpSession session = new MockHttpSession();

        /** 这一个浏览器的源地址；见 {@link AuthApiTest#ADDRESSES}。 */
        private final String address;

        private String csrfToken = "";

        Browser() {
            this("192.0.2." + (1 + ADDRESSES.incrementAndGet() % 60));
        }

        /** 指定源地址：限流那条测试要的正是「同一个地址反复尝试」。 */
        Browser(String address) {
            this.address = address;
        }

        /** The SPA cold start: 401, but the response hands out the CSRF cookie. */
        MockHttpServletResponse bootstrap() throws Exception {
            MockHttpServletResponse response = me();
            assertThat(response.getStatus()).isEqualTo(401);
            return response;
        }

        MockHttpServletResponse me() throws Exception {
            return exchange(get("/api/auth/me"));
        }

        MockHttpServletResponse login(String username, String password) throws Exception {
            return send(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("username", username)
                    .param("password", password));
        }

        MockHttpServletResponse logout() throws Exception {
            return send(post("/api/auth/logout"));
        }

        MockHttpServletResponse changePassword(String current, String replacement) throws Exception {
            return send(post("/api/auth/password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                            .formatted(current, replacement)));
        }

        MockHttpServletResponse changeDisplayName(String displayName) throws Exception {
            return send(patch("/api/auth/profile")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"displayName\":\"%s\"}".formatted(displayName)));
        }

        /** A write the way the SPA sends it: the cookie value echoed in the header. */
        MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
            return exchange(request.header(CSRF_HEADER, this.csrfToken));
        }

        /** The footgun: the cookie is present but the header was forgotten. */
        MockHttpServletResponse sendWithoutCsrfHeader(MockHttpServletRequestBuilder request)
                throws Exception {
            return exchange(request);
        }

        private MockHttpServletResponse exchange(MockHttpServletRequestBuilder request) throws Exception {
            request.session(this.session).with(raw -> {
                raw.setRemoteAddr(this.address);
                return raw;
            });
            if (StringUtils.hasText(this.csrfToken)) {
                request.cookie(new Cookie(CSRF_COOKIE, this.csrfToken));
            }
            MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
            String rotated = csrfCookieOf(response);
            if (StringUtils.hasText(rotated)) {
                this.csrfToken = rotated;
            }
            bodies.add(response.getContentAsString());
            return response;
        }
    }
}
