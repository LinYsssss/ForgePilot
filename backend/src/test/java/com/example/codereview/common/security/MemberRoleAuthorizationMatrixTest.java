package com.example.codereview.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.codereview.auth.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * P1a 成员角色授权矩阵(A1):对动作-角色矩阵逐条打真实请求。与
 * {@link ObjectLevelAuthorizationMatrixTest}(陌生人/匿名)互补,这里验证的是**成员内部**的
 * 角色边界:DEVELOPER 碰不到设置类端点,REVIEWER 碰不到上传/触发类端点,移交后角色互换生效。
 *
 * <p>技巧:项目不绑仓库。触发审查时角色不足是 403(先于仓库解析),角色够则落到 404
 * REPOSITORY_NOT_FOUND——用状态码区分"被角色拦下"与"过了角色但没仓库",不需要真实 git。
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MemberRoleAuthorizationMatrixTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    private Cookie leader;
    private Cookie developer;
    private Cookie reviewer;
    private String developerUsername;
    private String reviewerUsername;
    private Long developerUserId;
    private Long projectId;

    @BeforeAll
    void seed() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        developerUsername = "role_dev_" + suffix;
        reviewerUsername = "role_rev_" + suffix;
        leader = login("role_leader_" + suffix);
        developer = login(developerUsername);
        reviewer = login(reviewerUsername);
        projectId = createProject(leader, "角色矩阵项目");
        addMember(developerUsername, "DEVELOPER");
        addMember(reviewerUsername, "REVIEWER");
        developerUserId = memberUserId(developerUsername);
    }

    @Test
    @Order(1)
    void everyMemberCanReadButProjectSettingsStayWithLeader() throws Exception {
        mockMvc.perform(get("/api/projects/{id}", projectId).cookie(developer)).andExpect(status().isOk());
        mockMvc.perform(get("/api/projects/{id}", projectId).cookie(reviewer)).andExpect(status().isOk());
        mockMvc.perform(get("/api/projects/{id}/members", projectId).cookie(reviewer)).andExpect(status().isOk());
        // myRole 供前端裁剪操作入口
        mockMvc.perform(get("/api/projects/{id}", projectId).cookie(developer))
                .andExpect(jsonPath("$.data.myRole").value("DEVELOPER"));

        // 项目设置:DEVELOPER/REVIEWER 一律 403
        mockMvc.perform(put("/api/projects/{id}", projectId).cookie(developer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "改名", "defaultBranch", "main"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/projects/{id}", projectId).cookie(reviewer)).andExpect(status().isForbidden());
        // 成员管理:非 LEADER 403
        mockMvc.perform(post("/api/projects/{id}/members", projectId).cookie(developer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", reviewerUsername, "role", "REVIEWER"))))
                .andExpect(status().isForbidden());
        // 仓库绑定:非 LEADER 403
        mockMvc.perform(post("/api/projects/{id}/repository", projectId).cookie(developer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "repoUrl", "https://example.com/demo/repo.git", "provider", "GIT",
                                "defaultBranch", "main", "accessToken", ""))))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(2)
    void reviewTriggerNeedsDeveloperRoleAndUploadIsClosedToReviewer() throws Exception {
        // REVIEWER 触发审查:被角色拦下(403,先于仓库解析)
        mockMvc.perform(post("/api/projects/{id}/reviews/tasks", projectId).cookie(reviewer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        // DEVELOPER 过角色检查,落到"未绑定仓库"404——证明拦截点确实是角色而不是别的
        mockMvc.perform(post("/api/projects/{id}/reviews/tasks", projectId).cookie(developer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        // 知识上传:REVIEWER 403,DEVELOPER 通过(200)
        // getBytes() 走平台默认字符集(本机 GBK),中文必须显式 UTF-8,否则严格解码拒收(400)。
        var file = new org.springframework.mock.web.MockMultipartFile(
                "file", "policy.md", "text/markdown",
                "# 规范\n\n评审规范正文".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/projects/{id}/knowledge/documents", projectId)
                        .file(file).param("docType", "SECURITY").cookie(reviewer))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/projects/{id}/knowledge/documents", projectId)
                        .file(file).param("docType", "SECURITY").cookie(developer))
                .andExpect(status().isOk());
        // 知识重建索引(维护类):DEVELOPER 403
        mockMvc.perform(post("/api/projects/{id}/knowledge/reindex", projectId).cookie(developer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(3)
    void ownerRowIsImmutableUntilTransferred() throws Exception {
        Long leaderUserId = memberUserId("role_leader_" + developerUsername.substring("role_dev_".length()));
        mockMvc.perform(delete("/api/projects/{id}/members/{uid}", projectId, leaderUserId).cookie(leader))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/projects/{id}/members/{uid}", projectId, leaderUserId).cookie(leader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "DEVELOPER"))))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(4)
    void transferSwapsTheLeaderAndDemotesThePreviousOwner() throws Exception {
        // 非 owner 发起移交:403
        mockMvc.perform(post("/api/projects/{id}/members/transfer", projectId).cookie(developer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", developerUserId))))
                .andExpect(status().isForbidden());
        // owner 移交给 developer
        mockMvc.perform(post("/api/projects/{id}/members/transfer", projectId).cookie(leader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", developerUserId))))
                .andExpect(status().isOk());
        // 新负责人可改项目设置;原负责人已降为 DEVELOPER,改设置 403
        mockMvc.perform(put("/api/projects/{id}", projectId).cookie(developer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "移交后改名", "defaultBranch", "main"))))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/projects/{id}", projectId).cookie(leader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "原负责人改名", "defaultBranch", "main"))))
                .andExpect(status().isForbidden());
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
        Cookie cookie = login.getResponse().getCookie("reposage_auth");
        if (cookie == null) {
            throw new IllegalStateException("login did not set the auth cookie");
        }
        return cookie;
    }

    private Long createProject(Cookie cookie, String name) throws Exception {
        MvcResult project = mockMvc.perform(post("/api/projects")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name, "description", "member role matrix", "defaultBranch", "main"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(project.getResponse().getContentAsString())
                .path("data").path("projectId").asLong();
    }

    private void addMember(String username, String role) throws Exception {
        mockMvc.perform(post("/api/projects/{id}/members", projectId)
                        .cookie(leader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "role", role))))
                .andExpect(status().isOk());
    }

    private Long memberUserId(String username) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/members", projectId).cookie(leader))
                .andExpect(status().isOk())
                .andReturn();
        var data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        for (var node : data) {
            if (username.equals(node.path("username").asText())) {
                return node.path("userId").asLong();
            }
        }
        throw new IllegalStateException("member not found: " + username);
    }
}
