package com.example.codereview.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.codereview.auth.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

@SpringBootTest(properties = {
        "app.security.token-secret=test-secret",
        "app.security.token-encrypt-key=test-encrypt-key",
        "app.ai.provider=mock",
        "app.assistant.enabled=true",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false",
        "app.sandbox.archive-root=${java.io.tmpdir}/reposage-assistant-test-archives"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssistantFlowTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuthService authService;

    private Cookie leader;
    private Cookie reviewer;
    private Cookie stranger;
    private Long projectId;
    private Long otherProjectId;
    private Long requirementId;

    @BeforeAll
    void seed() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        leader = login("assistant_leader_" + suffix);
        reviewer = login("assistant_reviewer_" + suffix);
        stranger = login("assistant_stranger_" + suffix);
        projectId = createProject(leader, "助手项目");
        otherProjectId = createProject(reviewer, "另一个项目");
        addMember(leader, projectId, "assistant_reviewer_" + suffix, "REVIEWER");
        requirementId = createRequirement(leader, projectId);
    }

    @Test
    void memberReceivesContextThenMultipleDeltasThenDone() throws Exception {
        MvcResult started = mockMvc.perform(post(
                        "/api/projects/{projectId}/requirements/{requirementId}/assistant/stream",
                        projectId, requirementId)
                        .cookie(reviewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "message", "最容易漏掉什么？", "history", List.of()))))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(completed.getResponse().getContentType())
                .isEqualTo(MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8");
        assertThat(body.indexOf("event:context")).isLessThan(body.indexOf("event:delta"));
        assertThat(body.split("event:delta", -1).length - 1).isGreaterThanOrEqualTo(2);
        assertThat(body.indexOf("event:delta")).isLessThan(body.indexOf("event:done"));
        assertThat(body).contains("REQ-1", "AC-1", "建议先把实现拆成可验证的最小路径");
    }

    @Test
    void anonymousAndStrangerAreRejectedBeforeStreamStarts() throws Exception {
        String path = "/api/projects/" + projectId + "/requirements/" + requirementId + "/assistant/stream";
        String body = objectMapper.writeValueAsString(Map.of("message", "test", "history", List.of()));
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(path).cookie(stranger).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void requirementFromAnotherProjectIsHiddenBeforeStreamStarts() throws Exception {
        String path = "/api/projects/" + otherProjectId + "/requirements/" + requirementId
                + "/assistant/stream";
        String body = objectMapper.writeValueAsString(Map.of("message", "test", "history", List.of()));

        mockMvc.perform(post(path).cookie(reviewer).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    private Cookie login(String username) throws Exception {
        authService.createUser(username, "123456", "Tester", "DEVELOPER");
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", "123456"))))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getCookie("reposage_auth");
    }

    private Long createProject(Cookie owner, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects").cookie(owner).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name, "description", "assistant", "defaultBranch", "main"))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("projectId").asLong();
    }

    private void addMember(Cookie owner, Long pid, String username, String role) throws Exception {
        mockMvc.perform(post("/api/projects/{id}/members", pid).cookie(owner).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "role", role))))
                .andExpect(status().isOk());
    }

    private Long createRequirement(Cookie owner, Long pid) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects/{id}/requirements", pid).cookie(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "库存释放", "background", "订单取消", "description", "释放库存",
                                "priority", "HIGH", "acceptanceCriteria", List.of(Map.of("text", "五分钟内回补"))))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("requirementId").asLong();
    }
}
