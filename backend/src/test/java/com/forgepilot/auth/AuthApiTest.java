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
 * The auth contract of api-contract.md 1 against a real PostgreSQL and the real
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

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

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

    /** The exact body api-contract.md 1 promises for register, login and me. */
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

        private String csrfToken = "";

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
            request.session(this.session);
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
