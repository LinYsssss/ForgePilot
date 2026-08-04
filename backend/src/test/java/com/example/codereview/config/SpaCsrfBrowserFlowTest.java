package com.example.codereview.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.codereview.auth.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 真实浏览器序列下的 SPA CSRF 稳定性。此前无状态会话 + 默认 CsrfAuthenticationStrategy
 * 会让每个已认证响应都携带 XSRF Cookie 清除头(延迟重发永不触发),登录后的第一个写请求
 * 即 401/403——单靠 MockMvc 的 csrf() 后处理器发现不了,这里按浏览器的完整时序断言。
 */
@SpringBootTest(properties = {
        "app.security.token-secret=test-secret",
        "app.security.token-encrypt-key=test-encrypt-key",
        "app.security.csrf.enabled=true",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false"
})
@AutoConfigureMockMvc
class SpaCsrfBrowserFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Test
    void authenticatedReadsMustNotClearTheCsrfCookie() throws Exception {
        Session s = loginLikeABrowser("csrf_reader");

        MvcResult me = mockMvc.perform(get("/api/auth/me").cookie(s.cookies()))
                .andExpect(status().isOk())
                .andReturn();

        List<String> setCookies = me.getResponse().getHeaders("Set-Cookie");
        assertThat(setCookies)
                .as("已认证 GET 不得下发 XSRF-TOKEN 清除头,否则浏览器令牌被抹掉")
                .noneMatch(v -> v.startsWith("XSRF-TOKEN=;") || v.startsWith("XSRF-TOKEN=\"\""));
    }

    @Test
    void firstWriteAfterLoginSucceedsWithTheRotatedToken() throws Exception {
        Session s = loginLikeABrowser("csrf_writer");

        // 浏览器时序:登录后先读一个普通接口,再发写请求(此前这一步会丢令牌)
        mockMvc.perform(get("/api/auth/me").cookie(s.cookies())).andExpect(status().isOk());

        mockMvc.perform(post("/api/projects")
                        .cookie(s.cookies())
                        .header("X-XSRF-TOKEN", s.xsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "csrf-flow", "description", "", "defaultBranch", "main"))))
                .andExpect(status().isOk());
    }

    private record Session(Cookie auth, String xsrf) {
        Cookie[] cookies() { return new Cookie[] { auth, new Cookie("XSRF-TOKEN", xsrf) }; }
    }

    /** 复刻前端 client.js 的时序:bootstrap → login(带旧令牌) → 取轮换后的新令牌。 */
    private Session loginLikeABrowser(String username) throws Exception {
        authService.createUser(username, "123456", "Tester", "DEVELOPER");

        MvcResult bootstrap = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie preLogin = bootstrap.getResponse().getCookie("XSRF-TOKEN");
        assertThat(preLogin).as("bootstrap 必须下发令牌 Cookie").isNotNull();

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .cookie(preLogin)
                        .header("X-XSRF-TOKEN", preLogin.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", "123456"))))
                .andExpect(status().isOk())
                .andReturn();

        Cookie auth = login.getResponse().getCookie("reposage_auth");
        Cookie rotated = login.getResponse().getCookie("XSRF-TOKEN");
        assertThat(auth).isNotNull();
        assertThat(rotated).as("登录必须轮换并重新下发令牌").isNotNull();
        assertThat(rotated.getValue()).isNotEmpty().isNotEqualTo(preLogin.getValue());
        return new Session(auth, rotated.getValue());
    }
}
