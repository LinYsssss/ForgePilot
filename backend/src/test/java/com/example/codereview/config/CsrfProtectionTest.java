package com.example.codereview.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.codereview.auth.AuthService;
import com.example.codereview.common.security.CryptoService;
import com.example.codereview.common.security.CsrfTokenRotator;
import com.example.codereview.scm.ScmInstallation;
import com.example.codereview.scm.ScmInstallationRepository;
import com.example.codereview.scm.ScmProviderType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * CSRF 开关打开时的行为。默认部署是关闭的(跨线契约,合流阶段统一打开),因此这里显式打开,
 * 用来证明「打开后确实拦得住,且没有把 webhook 和正常前端流程一起拦死」。
 */
@SpringBootTest(properties = {
        "app.security.csrf.enabled=true",
        "app.security.token-secret=test-secret",
        "app.security.token-encrypt-key=test-encrypt-key",
        "app.git.allow-local-path=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false",
        "app.ratelimit.login-limit=1000"
})
@AutoConfigureMockMvc
class CsrfProtectionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private ScmInstallationRepository installations;

    @Autowired
    private CryptoService cryptoService;

    @Test
    void bootstrapEndpointIsPublicAndIssuesTheCookieWithoutEchoingTheToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.cookieName").value(CsrfTokenRotator.COOKIE_NAME))
                .andExpect(jsonPath("$.data.headerName").value(CsrfTokenRotator.HEADER_NAME))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie(CsrfTokenRotator.COOKIE_NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        assertThat(cookie.isHttpOnly()).isFalse(); // 前端必须读得到才能回传
        assertThat(result.getResponse().getContentAsString()).doesNotContain(cookie.getValue());
    }

    @Test
    void writeWithoutTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "nobody", "password", "123456"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeWithMismatchedTokenIsForbidden() throws Exception {
        Cookie csrf = bootstrapCsrfCookie();
        mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf)
                        .header(CsrfTokenRotator.HEADER_NAME, csrf.getValue() + "-tampered")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "nobody", "password", "123456"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginSucceedsWithTheTokenAndRotatesIt() throws Exception {
        String username = "csrf_user_" + System.nanoTime();
        authService.createUser(username, "123456", "Tester", "DEVELOPER");
        Cookie csrf = bootstrapCsrfCookie();

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf)
                        .header(CsrfTokenRotator.HEADER_NAME, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", "123456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        Cookie rotated = login.getResponse().getCookie(CsrfTokenRotator.COOKIE_NAME);
        assertThat(rotated).isNotNull();
        assertThat(rotated.getValue()).isNotBlank().isNotEqualTo(csrf.getValue());

        // 退出时作废:Cookie 被清空
        Cookie auth = login.getResponse().getCookie("reposage_auth");
        assertThat(auth).isNotNull();
        MvcResult logout = mockMvc.perform(post("/api/auth/logout")
                        .cookie(auth, rotated)
                        .header(CsrfTokenRotator.HEADER_NAME, rotated.getValue()))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cleared = logout.getResponse().getCookie(CsrfTokenRotator.COOKIE_NAME);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getValue()).isEmpty();
        assertThat(cleared.getMaxAge()).isZero();
    }

    /** 已认证但缺 CSRF 令牌的写请求同样要 403——不能因为带了合法会话就放过。 */
    @Test
    void authenticatedWriteStillNeedsTheToken() throws Exception {
        String username = "csrf_authz_" + System.nanoTime();
        authService.createUser(username, "123456", "Tester", "DEVELOPER");
        Cookie csrf = bootstrapCsrfCookie();
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf)
                        .header(CsrfTokenRotator.HEADER_NAME, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", "123456"))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie auth = login.getResponse().getCookie("reposage_auth");
        Cookie rotated = login.getResponse().getCookie(CsrfTokenRotator.COOKIE_NAME);

        mockMvc.perform(post("/api/projects")
                        .cookie(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "p", "description", "d"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/projects")
                        .cookie(auth, rotated)
                        .header(CsrfTokenRotator.HEADER_NAME, rotated.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "p", "description", "d"))))
                .andExpect(status().isOk());
    }

    /**
     * Webhook 来自 GitHub/GitLab,不可能带我们的 CSRF 令牌,必须免除;但免 CSRF 不等于免验签——
     * 签名错误依然要被拒。
     */
    @Test
    void webhookIsExemptFromCsrfButNotFromSignatureVerification() throws Exception {
        ScmInstallation installation = new ScmInstallation();
        installation.setProvider(ScmProviderType.GITHUB);
        installation.setExternalInstallationId("csrf-test-" + System.nanoTime());
        installation.setEncryptedWebhookSecret(cryptoService.encrypt("top-secret"));
        installation.setActive(true);
        installations.save(installation);

        String payload = "{\"action\":\"opened\",\"installation\":{\"id\":\""
                + installation.getExternalInstallationId() + "\"}}";

        mockMvc.perform(post("/api/webhooks/scm/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "delivery-" + System.nanoTime())
                        .header("X-Hub-Signature-256", "sha256=deadbeef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized()); // 401 = 走到了验签,而不是被 CSRF 挡在 403
    }

    private Cookie bootstrapCsrfCookie() throws Exception {
        Cookie cookie = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie(CsrfTokenRotator.COOKIE_NAME);
        assertThat(cookie).isNotNull();
        return cookie;
    }
}
