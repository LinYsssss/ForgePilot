package com.example.codereview.mq;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.codereview.auth.AuthService;
import com.example.codereview.review.ReviewTask;
import com.example.codereview.review.ReviewTaskRepository;
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
 * Negative-first coverage for P0-05.
 *
 * <p>{@code /api/mq/logs} used to query the repository straight from the controller by
 * {@code taskId}. Any authenticated user could therefore walk the identifier space and read other
 * projects' message payloads and error text — being logged in is not the same as being entitled.
 *
 * <p>Authentication is driven through the login cookie rather than a token lifted out of the JSON
 * body, so these tests keep working once the login response stops handing tokens to JavaScript.
 */
@SpringBootTest(properties = {
        "app.security.token-secret=test-secret",
        "app.security.token-encrypt-key=test-encrypt-key",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false"
})
@AutoConfigureMockMvc
class MqLogAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private ReviewTaskRepository tasks;

    @Autowired
    private MqTaskLogRepository logs;

    @Test
    void ownerCanReadTheLogsOfTheirOwnTask() throws Exception {
        Cookie owner = seedAndLogin("mq_owner_" + System.nanoTime());
        Long taskId = seedTaskWithLogs(owner, "所有者项目", 3);

        mockMvc.perform(get("/api/mq/logs").param("taskId", String.valueOf(taskId)).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    void strangerCannotReadAnotherProjectsLogs() throws Exception {
        Cookie owner = seedAndLogin("mq_victim_" + System.nanoTime());
        Long taskId = seedTaskWithLogs(owner, "受害者项目", 2);
        Cookie stranger = seedAndLogin("mq_attacker_" + System.nanoTime());

        mockMvc.perform(get("/api/mq/logs").param("taskId", String.valueOf(taskId)).cookie(stranger))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("PROJECT_FORBIDDEN"))
                // The rejection must not leak the contents it just refused to serve.
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void unknownTaskIsNotFound() throws Exception {
        Cookie user = seedAndLogin("mq_missing_" + System.nanoTime());

        mockMvc.perform(get("/api/mq/logs").param("taskId", "999999").cookie(user))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_TASK_NOT_FOUND"));
    }

    @Test
    void anonymousCallerIsRejected() throws Exception {
        mockMvc.perform(get("/api/mq/logs").param("taskId", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pageSizeIsCappedSoTheEndpointCannotBeUsedToDumpEverything() throws Exception {
        Cookie owner = seedAndLogin("mq_paged_" + System.nanoTime());
        Long taskId = seedTaskWithLogs(owner, "分页项目", 5);

        mockMvc.perform(get("/api/mq/logs")
                        .param("taskId", String.valueOf(taskId))
                        .param("size", "10000")
                        .cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));

        mockMvc.perform(get("/api/mq/logs")
                        .param("taskId", String.valueOf(taskId))
                        .param("size", "2")
                        .param("page", "1")
                        .cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(3));
    }

    @Test
    void negativePagingParametersFallBackToTheDefaults() throws Exception {
        Cookie owner = seedAndLogin("mq_negative_" + System.nanoTime());
        Long taskId = seedTaskWithLogs(owner, "非法分页项目", 1);

        mockMvc.perform(get("/api/mq/logs")
                        .param("taskId", String.valueOf(taskId))
                        .param("page", "-4")
                        .param("size", "-9")
                        .cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    // ------------------------------------------------------------------ helpers

    private Cookie seedAndLogin(String username) throws Exception {
        authService.createUser(username, "123456", "Tester", "DEVELOPER");
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", "123456"))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = login.getResponse().getCookie("reposage_auth");
        if (cookie == null) {
            throw new IllegalStateException("login did not set the auth cookie");
        }
        return cookie;
    }

    private Long seedTaskWithLogs(Cookie owner, String projectName, int logCount) throws Exception {
        Long projectId = createProject(owner, projectName);
        ReviewTask task = tasks.saveAndFlush(
                new ReviewTask(projectId, 1L, "commit-" + System.nanoTime(), null, "main", 1L, "diff"));
        for (int i = 0; i < logCount; i++) {
            logs.saveAndFlush(new MqTaskLog(
                    task.getId(), "message-" + i, "review.exchange", "review.task",
                    "code.review.task.queue", "{\"secret\":\"payload\"}", "PUBLISHED", 0, null));
        }
        return task.getId();
    }

    private Long createProject(Cookie owner, String name) throws Exception {
        MvcResult project = mockMvc.perform(post("/api/projects")
                        .cookie(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name, "description", "authz test", "defaultBranch", "main"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(project.getResponse().getContentAsString())
                .path("data").path("projectId").asLong();
    }
}
