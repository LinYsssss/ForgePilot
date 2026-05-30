package com.example.codereview.project;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.codereview.auth.AuthService;
import com.example.codereview.common.security.CryptoService;
import com.example.codereview.repo.CodeRepositoryEntity;
import com.example.codereview.repo.CodeRepositoryJpaRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "app.security.token-secret=test-secret",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false"
})
@AutoConfigureMockMvc
class AuthProjectIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CodeRepositoryJpaRepository repositories;

    @Autowired
    private CryptoService cryptoService;

    @Autowired
    private AuthService authService;

    @Test
    void loginThenCreateAndListProject() throws Exception {
        String username = "tester_" + System.nanoTime();
        String token = seedAndLogin(username);

        mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "测试项目",
                                "description", "integration test",
                                "defaultBranch", "main"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("测试项目"));

        mockMvc.perform(get("/api/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].name").value("测试项目"));
    }

    @Test
    void rejectProjectAccessWithoutToken() throws Exception {
        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isForbidden());
    }

    @Test
    void bindRepositoryEncryptsAccessToken() throws Exception {
        String token = seedAndLogin("repo_tester_" + System.nanoTime());
        Long projectId = createProjectAndGetId(token, "仓库加密测试");

        mockMvc.perform(post("/api/projects/{projectId}/repository", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "repoUrl", "https://example.com/demo/repo.git",
                                "provider", "GIT",
                                "defaultBranch", "main",
                                "accessToken", "plain-secret-token"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());

        CodeRepositoryEntity repository = repositories.findByProjectId(projectId).orElseThrow();

        assert repository.getAccessTokenCiphertext() != null;
        org.assertj.core.api.Assertions.assertThat(repository.getAccessTokenCiphertext()).startsWith("v1:");
        org.assertj.core.api.Assertions.assertThat(repository.getAccessTokenCiphertext()).doesNotContain("plain-secret-token");
        org.assertj.core.api.Assertions.assertThat(cryptoService.decrypt(repository.getAccessTokenCiphertext()))
                .isEqualTo("plain-secret-token");
    }

    private String seedAndLogin(String username) throws Exception {
        authService.createUser(username, "123456", "Tester", "DEVELOPER");
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "123456"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    private Long createProjectAndGetId(String token, String name) throws Exception {
        MvcResult project = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "description", "integration test",
                                "defaultBranch", "main"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(project.getResponse().getContentAsString()).path("data").path("projectId").asLong();
    }
}
