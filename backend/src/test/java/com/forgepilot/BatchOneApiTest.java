package com.forgepilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 批次 1 在真实 HTTP 之上的完整闭环：注册、登录、创建项目、添加成员、
 * 写一条带验收条件的需求、冻结它、指派它，再把修订历史读回来。
 * 本批次其余测试都只针对单个切片；只有这里真正证明了它们之间的接线——
 * 安全过滤器链、CSRF、错误映射、JSON 结构。
 */
@SpringBootTest
class BatchOneApiTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void aLeaderWalksTheWholeLoopAndTheDeveloperSeesItReadOnly() throws Exception {
        Client leader = register();
        Client developer = register();

        long project = leader.post("/api/projects", """
                {"name": "ForgePilot"}""").path("id").asLong();

        leader.post("/api/projects/" + project + "/members/batch", """
                {"members": [{"userId": %d, "roles": ["DEVELOPER"]}]}"""
                .formatted(developer.userId));

        long requirement = leader.post("/api/projects/" + project + "/requirements", """
                {"title": "Log in with a local account",
                 "background": "Reviewers need identity before anything else.",
                 "description": "Form login backed by user_account.",
                 "acceptanceCriteria": [{"text": "Wrong password and unknown user look identical."},
                                        {"text": "Logging out kills the session."}]}""")
                .path("id").asLong();

        String base = "/api/projects/" + project + "/requirements/" + requirement;

        JsonNode created = leader.read(base);
        assertThat(created.path("status").asString()).isEqualTo("DRAFT");
        assertThat(created.path("currentRevision").path("seq").asInt()).isEqualTo(1);
        // 批次 3 已经把审查活动状态整个移出了这个响应。它由 pull_request 与 review
        // 共同推导，而依赖箭头的方向是 review -> requirement，
        // 因此 requirement 要算它就必然让功能依赖图成环。
        // 在这里断言它**不存在**就是那道边界检查：一旦它又回来了，
        // 说明有人朝错误的方向伸手了。
        assertThat(created.has("reviewActivity")).isFalse();
        JsonNode firstCriteria = created.path("currentRevision").path("acceptanceCriteria");
        assertThat(firstCriteria.size()).isEqualTo(2);
        String firstKey = firstCriteria.get(0).path("acKey").asString();
        String secondKey = firstCriteria.get(1).path("acKey").asString();
        assertThat(firstKey).isNotBlank().isNotEqualTo(secondKey);

        // DEVELOPER 能读项目内的一切，但什么都改不了。
        assertThat(developer.read(base).path("id").asLong()).isEqualTo(requirement);
        developer.postExpecting(base + "/status", """
                {"status": "READY"}""", status().isForbidden());

        leader.post(base + "/status", """
                {"status": "READY"}""");

        // 已冻结：需求一旦进入 READY，DRAFT 编辑通道就关闭了。
        // 作出这个判断需要看当前状态，因此返回 409（api-contract 0）。
        leader.patchExpecting(base, """
                {"title": "Sneak an edit in", "acceptanceCriteria": [{"acKey": "%s", "text": "changed"}]}"""
                .formatted(firstKey), status().isConflict());

        // 首次指派会在同一个事务里把 READY 推进到 IN_DEVELOPMENT。
        JsonNode assigned = leader.post(base + "/assignee", """
                {"userId": %d}""".formatted(developer.userId));
        assertThat(assigned.path("status").asString()).isEqualTo("IN_DEVELOPMENT");
        assertThat(assigned.path("assigneeId").asLong()).isEqualTo(developer.userId);
        assertThat(assigned.path("assigneeUsername").asString()).isEqualTo(developer.username);

        // 重新指派既不会把状态往回退，也不会把它往前推。
        assertThat(leader.post(base + "/assignee", """
                {"userId": %d}""".formatted(leader.userId)).path("status").asString())
                .isEqualTo("IN_DEVELOPMENT");

        // 发布新修订会保留每一个已有的 acKey，同时可以自由重排顺序。
        JsonNode published = leader.post(base + "/revisions", """
                {"title": "Log in with a local account",
                 "changeReason": "Reviewers asked for an explicit lockout rule.",
                 "acceptanceCriteria": [{"acKey": "%s", "text": "Logging out kills the session."},
                                        {"acKey": "%s", "text": "Wrong password and unknown user look identical."},
                                        {"text": "Changing the password kills every other session."}]}"""
                .formatted(secondKey, firstKey));
        assertThat(published.path("currentRevision").path("seq").asInt()).isEqualTo(2);

        JsonNode history = leader.read(base + "/revisions");
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).path("seq").asInt()).isEqualTo(1);
        assertThat(history.get(0).path("acceptanceCriteria").size()).isEqualTo(2);
        assertThat(history.get(1).path("changeReason").asString())
                .isEqualTo("Reviewers asked for an explicit lockout rule.");

        // 同一条验收条件跨修订保住了它的 key，尽管它的位置变了。
        JsonNode republished = history.get(1).path("acceptanceCriteria");
        assertThat(republished.get(0).path("acKey").asString()).isEqualTo(secondKey);
        assertThat(republished.get(1).path("acKey").asString()).isEqualTo(firstKey);
        assertThat(republished.get(2).path("acKey").asString()).isNotIn(firstKey, secondKey);
    }

    @Test
    void anotherProjectsIdsAreInvisibleOverHttp() throws Exception {
        Client owner = register();
        Client outsider = register();

        long foreign = owner.post("/api/projects", """
                {"name": "Private"}""").path("id").asLong();
        long foreignRequirement = owner.post("/api/projects/" + foreign + "/requirements", """
                {"title": "Secret", "acceptanceCriteria": [{"text": "hidden"}]}""")
                .path("id").asLong();
        long ownProject = outsider.post("/api/projects", """
                {"name": "Mine"}""").path("id").asLong();

        // 别人的项目与一个从未签发过的 id 答得一模一样，因此无法探测存在性。
        outsider.readExpecting("/api/projects/" + foreign, status().isNotFound());
        outsider.readExpecting("/api/projects/" + (foreign + 9_000), status().isNotFound());
        outsider.readExpecting("/api/projects/" + foreign + "/members", status().isNotFound());
        outsider.readExpecting("/api/projects/" + foreign + "/requirements/" + foreignRequirement,
                status().isNotFound());

        // 也不能把一条外项目的需求硬拽进调用方自己拥有的项目里。
        outsider.readExpecting("/api/projects/" + ownProject + "/requirements/" + foreignRequirement,
                status().isNotFound());
        outsider.postExpecting("/api/projects/" + ownProject + "/requirements/" + foreignRequirement
                + "/status", """
                {"status": "CANCELED"}""", status().isNotFound());

        assertThat(outsider.read("/api/projects").size()).isEqualTo(1);
    }

    /**
     * 三个 D022 的 DELETE 端点在**真实 HTTP** 之上的接线。Service 层测试证明了删除
     * 语义，但证明不了路径映射、204 状态码，以及经过安全过滤器链后的答案——一个写错
     * 的路径在 Service 测试里全绿，到了前端就是 404。
     *
     * <p>同时把 AC13 的跨项目隔离补到这一层：外项目的 id 与从未签发过的 id 必须答得
     * 一模一样，否则状态码本身就泄露了「那个 id 存在」。
     */
    @Test
    void theThreeDeleteEndpointsAreWiredAndInvisibleAcrossProjects() throws Exception {
        Client owner = register();
        Client outsider = register();

        long project = owner.post("/api/projects", """
                {"name": "Removals"}""").path("id").asLong();
        long outsiderProject = outsider.post("/api/projects", """
                {"name": "Elsewhere"}""").path("id").asLong();
        long requirement = owner.post("/api/projects/" + project + "/requirements", """
                {"title": "To be canceled", "acceptanceCriteria": [{"text": "anything"}]}""")
                .path("id").asLong();

        String requirementPath = "/api/projects/" + project + "/requirements/" + requirement;

        // 非作废需求：端点确实存在（不是 404），但被状态门禁以 409 拒绝。
        owner.deleteExpecting(requirementPath, status().isConflict());

        owner.post(requirementPath + "/status", """
                {"status": "CANCELED"}""");
        owner.deleteExpecting(requirementPath, status().isNoContent());

        // 软删之后它从产品面消失，重复删除与「从不存在」同答。
        owner.readExpecting(requirementPath, status().isNotFound());
        owner.deleteExpecting(requirementPath, status().isNotFound());
        assertThat(owner.read("/api/projects/" + project + "/requirements").size()).isZero();

        // 成员与知识文档端点：跨项目调用与未签发的 id 不可区分。
        outsider.deleteExpecting("/api/projects/" + project + "/members/" + owner.userId,
                status().isNotFound());
        outsider.deleteExpecting("/api/projects/" + project + "/knowledge/documents/1",
                status().isNotFound());
        outsider.deleteExpecting("/api/projects/" + (project + 9_000) + "/members/" + owner.userId,
                status().isNotFound());
        // 也不能把一条外项目的需求经由自己拥有的项目删掉。
        outsider.deleteExpecting(
                "/api/projects/" + outsiderProject + "/requirements/" + requirement,
                status().isNotFound());

        // 唯一 LEADER 移除自己：端点在，但被 409 拒绝而不是让项目失去负责人。
        owner.deleteExpecting("/api/projects/" + project + "/members/" + owner.userId,
                status().isConflict());
        assertThat(owner.read("/api/projects/" + project + "/members").size()).isEqualTo(1);
    }

    @Test
    void writesWithoutTheCsrfHeaderAreRejected() throws Exception {
        Client leader = register();

        mockMvc.perform(MockMvcRequestBuilders.post("/api/projects")
                        .session(leader.session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "No token"}"""))
                .andExpect(status().isForbidden());

        assertThat(leader.read("/api/projects").size()).isZero();
    }

    @Test
    void anonymousCallersGetTheSameErrorShapeAsEveryoneElse() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.traceId").exists())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("password");
    }

    // -------------------------------------------------------------------- 客户端

    private Client register() throws Exception {
        Client client = new Client("pilot-" + SEQUENCE.incrementAndGet());
        client.bootstrapCsrf();
        client.post("/api/auth/register", """
                {"username": "%s", "displayName": "Test User", "password": "%s"}"""
                .formatted(client.username, Client.PASSWORD));
        client.login();
        return client;
    }

    /** 一个浏览器：它的会话，加上它会以 header 回显的那个 CSRF cookie。 */
    private final class Client {

        private static final String PASSWORD = "correct-horse-battery";

        private final MockHttpSession session = new MockHttpSession();
        private final String username;
        private Cookie csrf;
        private long userId;

        private Client(String username) {
            this.username = username;
        }

        private void bootstrapCsrf() throws Exception {
            // 匿名请求，但它仍然必须把 JS 客户端所需的 token 发出来。
            csrf = mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me").session(session))
                    .andReturn().getResponse().getCookie("XSRF-TOKEN");
            assertThat(csrf).as("GET /api/auth/me must issue the XSRF-TOKEN cookie").isNotNull();
        }

        private void login() throws Exception {
            MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                            .session(session)
                            .param("username", username)
                            .param("password", PASSWORD)
                            .cookie(csrf)
                            .header("X-XSRF-TOKEN", csrf.getValue()))
                    .andExpect(status().isOk())
                    .andReturn();
            // Spring Security 会在认证时重新签发 token；这里保留新的那个。
            Cookie reissued = result.getResponse().getCookie("XSRF-TOKEN");
            if (reissued != null && reissued.getValue() != null && !reissued.getValue().isEmpty()) {
                csrf = reissued;
            }
            userId = body(result).path("id").asLong();
        }

        private JsonNode read(String path) throws Exception {
            return body(mockMvc.perform(MockMvcRequestBuilders.get(path).session(session))
                    .andExpect(status().is2xxSuccessful()).andReturn());
        }

        private void readExpecting(String path, ResultMatcher expected) throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.get(path).session(session)).andExpect(expected);
        }

        private JsonNode post(String path, String payload) throws Exception {
            return body(mockMvc.perform(write(MockMvcRequestBuilders.post(path), payload))
                    .andExpect(status().is2xxSuccessful()).andReturn());
        }

        private void postExpecting(String path, String payload, ResultMatcher expected) throws Exception {
            mockMvc.perform(write(MockMvcRequestBuilders.post(path), payload)).andExpect(expected);
        }

        private JsonNode patch(String path, String payload) throws Exception {
            return body(mockMvc.perform(write(MockMvcRequestBuilders.patch(path), payload))
                    .andExpect(status().is2xxSuccessful()).andReturn());
        }

        private void patchExpecting(String path, String payload, ResultMatcher expected) throws Exception {
            mockMvc.perform(write(MockMvcRequestBuilders.patch(path), payload)).andExpect(expected);
        }

        private void deleteExpecting(String path, ResultMatcher expected) throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.delete(path)
                            .session(session)
                            .cookie(csrf)
                            .header("X-XSRF-TOKEN", csrf.getValue()))
                    .andExpect(expected);
        }

        private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, String payload) {
            return request.session(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload)
                    .cookie(csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue());
        }
    }

    private JsonNode body(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertThat(content).as("no response body to read").isNotEmpty();
        return json.readTree(content);
    }
}
