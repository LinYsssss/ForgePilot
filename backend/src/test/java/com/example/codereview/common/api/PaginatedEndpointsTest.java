package com.example.codereview.common.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.codereview.auth.AuthService;
import com.example.codereview.knowledge.KnowledgeDocument;
import com.example.codereview.knowledge.KnowledgeDocumentRepository;
import com.example.codereview.review.ReviewTask;
import com.example.codereview.review.ReviewTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Checks that the growing collections actually honour the frozen page envelope.
 *
 * <p>These endpoints previously returned every row a project had ever accumulated, so a long-lived
 * project meant an unbounded response. The point of the assertions is the ceiling: a client asking
 * for ten thousand rows must not get them.
 */
@SpringBootTest(properties = {
        "app.security.token-secret=test-secret",
        "app.security.token-encrypt-key=test-encrypt-key",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaginatedEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private ReviewTaskRepository tasks;

    @Autowired
    private KnowledgeDocumentRepository documents;

    private Cookie owner;
    private Long projectId;

    @BeforeAll
    void seed() throws Exception {
        owner = login("page_owner_" + System.nanoTime());
        projectId = createProject(owner);
        bindRepository(owner, projectId);
        for (int i = 0; i < 25; i++) {
            tasks.saveAndFlush(new ReviewTask(
                    projectId, 1L, "commit-" + i + "-" + System.nanoTime(), null, "main", 1L, "diff"));
            documents.saveAndFlush(new KnowledgeDocument(projectId, 1L, "SPEC", "doc-" + i + ".md", "内容"));
        }
    }

    @Test
    void reviewTasksAreCappedAtTheAgreedMaximum() throws Exception {
        mockMvc.perform(get("/api/projects/{id}/reviews/tasks", projectId)
                        .param("size", "10000").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.totalElements").value(25));
    }

    @Test
    void reviewTasksDefaultToTwentyPerPage() throws Exception {
        mockMvc.perform(get("/api/projects/{id}/reviews/tasks", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items.length()").value(20))
                .andExpect(jsonPath("$.data.totalPages").value(2));
    }

    @Test
    void reviewTasksServeTheRequestedPage() throws Exception {
        mockMvc.perform(get("/api/projects/{id}/reviews/tasks", projectId)
                        .param("page", "1").param("size", "20").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(5));
    }

    @Test
    void reviewReportsUseThePageEnvelope() throws Exception {
        mockMvc.perform(get("/api/projects/{id}/reviews/reports", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").exists());
    }

    @Test
    void knowledgeDocumentsAreCappedAndPaged() throws Exception {
        mockMvc.perform(get("/api/projects/{id}/knowledge/documents", projectId)
                        .param("size", "10000").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.totalElements").value(25));

        mockMvc.perform(get("/api/projects/{id}/knowledge/documents", projectId)
                        .param("size", "5").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(5))
                .andExpect(jsonPath("$.data.totalPages").value(5));
    }

    @Test
    void illegalPagingParametersFallBackInsteadOfFailing() throws Exception {
        mockMvc.perform(get("/api/projects/{id}/knowledge/documents", projectId)
                        .param("page", "-7").param("size", "-3").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    void pageBeyondTheEndIsEmptyRatherThanAnError() throws Exception {
        mockMvc.perform(get("/api/projects/{id}/reviews/tasks", projectId)
                        .param("page", "99").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(25));
    }

    // ------------------------------------------------------------------ helpers

    private Cookie login(String username) throws Exception {
        authService.createUser(username, "123456", "Tester", "DEVELOPER");
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", "123456"))))
                .andExpect(status().isOk())
                .andReturn();
        return login.getResponse().getCookie("reposage_auth");
    }

    private Long createProject(Cookie cookie) throws Exception {
        MvcResult project = mockMvc.perform(post("/api/projects")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "分页项目", "description", "pagination", "defaultBranch", "main"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(project.getResponse().getContentAsString())
                .path("data").path("projectId").asLong();
    }

    private void bindRepository(Cookie cookie, Long projectId) throws Exception {
        mockMvc.perform(post("/api/projects/{projectId}/repository", projectId)
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "repoUrl", "https://example.com/demo/repo.git",
                                "provider", "GIT",
                                "defaultBranch", "main",
                                "accessToken", ""))))
                .andExpect(status().isOk());
    }
}
