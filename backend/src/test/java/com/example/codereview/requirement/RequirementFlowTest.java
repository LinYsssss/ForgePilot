package com.example.codereview.requirement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.codereview.auth.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
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
 * P1b 需求域全链路(A1/A2):REQ 取号、状态机合法/非法边、指派守卫、内容锁定、
 * 角色负面用例(REVIEWER 不可建、非指派人不可提审、被指派 DEVELOPER 可提审)。
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
class RequirementFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    private Cookie leader;
    private Cookie developer;
    private Cookie reviewer;
    private Long developerUserId;
    private Long projectId;
    private Long requirementId;

    @BeforeAll
    void seed() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        leader = login("req_leader_" + suffix);
        developer = login("req_dev_" + suffix);
        reviewer = login("req_rev_" + suffix);
        projectId = createProject();
        addMember("req_dev_" + suffix, "DEVELOPER");
        addMember("req_rev_" + suffix, "REVIEWER");
        developerUserId = memberUserId("req_dev_" + suffix);
    }

    @Test
    @Order(1)
    void creationIsLeaderOnlyAndSeqIsMonotonic() throws Exception {
        mockMvc.perform(post("/api/projects/{id}/requirements", projectId).cookie(reviewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("越权需求")))
                .andExpect(status().isForbidden());

        MvcResult first = mockMvc.perform(post("/api/projects/{id}/requirements", projectId).cookie(leader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("订单取消库存释放")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("REQ-1"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.acceptanceCriteria.length()").value(2))
                .andReturn();
        requirementId = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("requirementId").asLong();

        mockMvc.perform(post("/api/projects/{id}/requirements", projectId).cookie(leader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("第二条需求")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("REQ-2"));

        // 成员均可读列表
        mockMvc.perform(get("/api/projects/{id}/requirements", projectId).cookie(reviewer))
                .andExpect(status().isOk());
    }

    @Test
    @Order(2)
    void stateMachineRejectsIllegalEdgesAndGuardsAssignment() throws Exception {
        // DRAFT → DONE:非法
        transition(leader, "DONE").andExpect(status().isConflict());
        // DRAFT → READY → NEEDS_IMPROVEMENT → READY:合法回退边
        transition(leader, "READY").andExpect(status().isOk());
        transition(leader, "NEEDS_IMPROVEMENT").andExpect(status().isOk());
        transition(leader, "READY").andExpect(status().isOk());
        // 未指派 → IN_DEVELOPMENT:守卫拒绝
        transition(leader, "IN_DEVELOPMENT").andExpect(status().isConflict());
        // 指派非成员:404;指派成员:200
        mockMvc.perform(post("/api/projects/{pid}/requirements/{rid}/assign", projectId, requirementId)
                        .cookie(leader).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", 999999L))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/projects/{pid}/requirements/{rid}/assign", projectId, requirementId)
                        .cookie(leader).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", developerUserId))))
                .andExpect(status().isOk());
        transition(leader, "IN_DEVELOPMENT").andExpect(status().isOk());
    }

    @Test
    @Order(3)
    void contentLocksAfterDevelopmentStartsAndAssigneeSubmitsForReview() throws Exception {
        // 进入开发后编辑内容:409 REQUIREMENT_LOCKED
        mockMvc.perform(put("/api/projects/{pid}/requirements/{rid}", projectId, requirementId)
                        .cookie(leader).contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("改标题")))
                .andExpect(status().isConflict());
        // 非指派人(REVIEWER)提审:403;被指派 DEVELOPER 提审:200
        transition(reviewer, "IN_REVIEW").andExpect(status().isForbidden());
        transition(developer, "IN_REVIEW").andExpect(status().isOk());
        // LEADER 验收 DONE;终态后不可再取消
        transition(leader, "DONE").andExpect(status().isOk());
        transition(leader, "CANCELED").andExpect(status().isConflict());
    }

    @Test
    @Order(4)
    void qualityCheckRunsForDeveloperAndIsClosedToReviewer() throws Exception {
        // REVIEWER 触发体检:403(角色矩阵:体检触发 = LEADER/DEVELOPER)
        mockMvc.perform(post("/api/projects/{pid}/requirements/{rid}/check", projectId, requirementId)
                        .cookie(reviewer).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        // LEADER 触发:mock provider 路径,返回六维结构化报告(规则层至少能给出条目容器)
        mockMvc.perform(post("/api/projects/{pid}/requirements/{rid}/check", projectId, requirementId)
                        .cookie(leader).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.round").value(1))
                .andExpect(jsonPath("$.data.dimensions.length()").value(6));
        // 历史列表:成员可读,已有 1 轮
        mockMvc.perform(get("/api/projects/{pid}/requirements/{rid}/check-reports", projectId, requirementId)
                        .cookie(reviewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions transition(Cookie who, String status) throws Exception {
        return mockMvc.perform(post("/api/projects/{pid}/requirements/{rid}/status", projectId, requirementId)
                .cookie(who).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("status", status))));
    }

    private String saveBody(String title) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "title", title,
                "background", "促销期订单量大",
                "description", "用户取消未支付订单时释放占用库存",
                "priority", "HIGH",
                "acceptanceCriteria", List.of(
                        Map.of("text", "取消后 5 分钟内库存回补"),
                        Map.of("text", "重复取消不重复回补"))));
    }

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

    private Long createProject() throws Exception {
        MvcResult project = mockMvc.perform(post("/api/projects")
                        .cookie(leader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "需求域项目", "description", "requirement flow", "defaultBranch", "main"))))
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
