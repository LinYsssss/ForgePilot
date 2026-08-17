package com.example.codereview.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.codereview.agent.orchestration.AgentScmContext;
import com.example.codereview.agent.orchestration.AgentScmContextRepository;
import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.auth.AuthService;
import com.example.codereview.finding.Finding;
import com.example.codereview.finding.FindingRepository;
import com.example.codereview.finding.FindingSeverity;
import com.example.codereview.scm.NormalizedPullRequestEvent;
import com.example.codereview.scm.ScmInstallation;
import com.example.codereview.scm.ScmProviderType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Reverse-authorization matrix over every id-bearing endpoint.
 *
 * <p>The hardening plan is explicit that object-level safety must be demonstrated rather than
 * declared after reading the code, because "the controller takes a CurrentUser" says nothing about
 * whether it then checks the resource actually belongs to that user. Each case below drives a real
 * request from a second account against the first account's resource.
 *
 * <p>Two rules the matrix enforces:
 *
 * <ul>
 *   <li>a stranger never gets 2xx — 403 or 404 are both acceptable, leaking existence is not;
 *   <li>an anonymous caller always gets 401, never a redirect or a partial response.
 * </ul>
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
class ObjectLevelAuthorizationMatrixTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AgentRunRepository agentRuns;

    @Autowired
    private AgentScmContextRepository scmContexts;

    @Autowired
    private FindingRepository findings;

    private Cookie owner;
    private Cookie stranger;
    private Cookie projectMember;
    private String projectMemberUsername;
    private Long projectMemberUserId;
    private Long ownedProjectId;
    private Long ownedFindingId;

    @BeforeAll
    void seed() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        owner = login("matrix_owner_" + suffix);
        stranger = login("matrix_stranger_" + suffix);
        projectMemberUsername = "matrix_member_" + suffix;
        projectMember = login(projectMemberUsername);
        ownedProjectId = createProject(owner, "矩阵项目");
        addMember(owner, ownedProjectId, projectMemberUsername, "DEVELOPER");
        projectMemberUserId = findMemberUserId(owner, ownedProjectId, projectMemberUsername);
        // The review endpoints resolve the bound repository before authorizing, so without one they
        // return 404 to owner and stranger alike — and the matrix would pass for the wrong reason.
        bindRepository(owner, ownedProjectId);
        ownedFindingId = createFinding();
    }

    /**
     * Endpoints whose path carries a project id. A stranger substituting somebody else's project id
     * is the most direct form of the attack.
     */
    @TestFactory
    List<DynamicTest> strangerCannotReachProjectScopedEndpoints() {
        record Case(String name, String method, String path) {
        }
        List<Case> cases = List.of(
                new Case("project detail", "GET", "/api/projects/{id}"),
                new Case("project update", "POST", "/api/projects/{id}"),
                new Case("project delete", "DELETE", "/api/projects/{id}"),
                new Case("repository detail", "GET", "/api/projects/{id}/repository"),
                new Case("repository commits", "GET", "/api/projects/{id}/repository/commits"),
                new Case("review tasks", "GET", "/api/projects/{id}/reviews/tasks"),
                new Case("review reports", "GET", "/api/projects/{id}/reviews/reports"),
                new Case("knowledge documents", "GET", "/api/projects/{id}/knowledge/documents"),
                new Case("knowledge reindex", "POST", "/api/projects/{id}/knowledge/reindex"),
                new Case("pull requests", "GET", "/api/projects/{id}/pull-requests"),
                new Case("agent runs by project", "GET", "/api/agent-runs/project/{id}"),
                new Case("project findings", "GET", "/api/projects/{id}/findings")
        );
        return cases.stream()
                .map(testCase -> DynamicTest.dynamicTest(testCase.name(), () ->
                        expectDenied(perform(testCase.method(),
                                testCase.path().replace("{id}", String.valueOf(ownedProjectId)), stranger))))
                .toList();
    }

    /**
     * Endpoints keyed by a resource id with no project in the path. These rely on walking a chain
     * back to the owning project, which is where an authorization check is easiest to forget.
     */
    @TestFactory
    List<DynamicTest> strangerCannotEnumerateResourceIdentifiers() {
        record Case(String name, String method, String path) {
        }
        List<Case> cases = List.of(
                new Case("agent run detail", "GET", "/api/agent-runs/1"),
                new Case("agent run timeline", "GET", "/api/agent-runs/1/timeline"),
                new Case("agent run cancel", "POST", "/api/agent-runs/1/cancel"),
                new Case("agent run retry", "POST", "/api/agent-runs/1/retry"),
                new Case("mq logs", "GET", "/api/mq/logs?taskId=1"),
                new Case("ai call logs", "GET", "/api/ai/logs?taskId=1"),
                new Case("issue feedback", "GET", "/api/review-issues/1/feedback")
        );
        return cases.stream()
                .map(testCase -> DynamicTest.dynamicTest(testCase.name(), () ->
                        expectDenied(perform(testCase.method(), testCase.path(), stranger))))
                .toList();
    }

    @Test
    void strangerAndProjectMemberCannotMutateSomeoneElsesFinding() throws Exception {
        String lifecyclePath = "/api/projects/" + ownedProjectId
                + "/findings/" + ownedFindingId + "/lifecycle";
        String assignPath = "/api/projects/" + ownedProjectId
                + "/findings/" + ownedFindingId + "/assign";

        expectForbiddenOrNotFound(postJson(lifecyclePath, stranger, Map.of("action", "CONFIRMED")));
        expectForbiddenOrNotFound(postJson(assignPath, stranger, Map.of("userId", projectMemberUserId)));
        expectForbiddenOrNotFound(postJson(lifecyclePath, projectMember, Map.of("action", "CONFIRMED")));
        expectForbiddenOrNotFound(postJson(assignPath, projectMember, Map.of("userId", projectMemberUserId)));
    }

    @Test
    void anonymousFindingMutationsAreAlwaysRejected() throws Exception {
        String lifecyclePath = "/api/projects/" + ownedProjectId
                + "/findings/" + ownedFindingId + "/lifecycle";
        String assignPath = "/api/projects/" + ownedProjectId
                + "/findings/" + ownedFindingId + "/assign";

        postJson(lifecyclePath, null, Map.of("action", "CONFIRMED"))
                .andExpect(status().isUnauthorized());
        postJson(assignPath, null, Map.of("userId", projectMemberUserId))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 反馈提交端点单列:共享的 perform 只发空体 {@code {}},会在进入服务层鉴权前就被
     * Bean Validation 拦成 400——矩阵会"因错误的原因通过"。这里带合法载荷打到真实存在的
     * run 上,并显式只接受 403/404:载荷若因契约漂移变回 400,此用例失败而不是假绿,
     * 保证拒绝确实来自对象级授权而不是参数校验。
     */
    @Test
    void strangerCannotAttachFeedbackToSomeoneElsesRun() throws Exception {
        Long runId = agentRuns.save(new AgentRun(ownedProjectId, 1L, null,
                "matrix-feedback-" + System.nanoTime(), "headsha0001")).getId();
        mockMvc.perform(post("/api/agent-runs/{id}/feedback", runId)
                        .cookie(stranger)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "MISS_REPORT", "path", "src/A.java", "line", 1, "note", "矩阵探测"))))
                .andExpect(result -> {
                    int statusCode = result.getResponse().getStatus();
                    if (statusCode != 403 && statusCode != 404) {
                        throw new AssertionError("expected the object-level guard to answer 403/404 but got "
                                + statusCode + " — a 400 would mean validation fired before authorization");
                    }
                });
    }

    @TestFactory
    List<DynamicTest> anonymousCallerIsAlwaysRejected() {
        List<String> paths = List.of(
                "/api/projects",
                "/api/projects/1",
                "/api/projects/1/reviews/tasks",
                "/api/projects/1/knowledge/documents",
                "/api/agent-runs/1",
                "/api/agent-runs/project/1",
                "/api/agent-runs/1/feedback",
                "/api/feedback/export",
                "/api/mq/logs?taskId=1",
                "/api/ai/logs?taskId=1",
                "/api/review-issues/1/feedback",
                "/api/scm/installations"
        );
        return paths.stream()
                .map(path -> DynamicTest.dynamicTest("anonymous " + path, () ->
                        perform("GET", path, null).andExpect(status().isUnauthorized())))
                .toList();
    }

    @Test
    void ownerStillReachesTheirOwnProject() throws Exception {
        // Guards against the matrix passing for the wrong reason: if everything 403'd, the tests
        // above would be satisfied by an endpoint that is simply broken.
        perform("GET", "/api/projects/" + ownedProjectId, owner).andExpect(status().isOk());
        perform("GET", "/api/projects/" + ownedProjectId + "/reviews/tasks", owner)
                .andExpect(status().isOk());
        perform("GET", "/api/projects/" + ownedProjectId + "/knowledge/documents", owner)
                .andExpect(status().isOk());
        perform("GET", "/api/projects/" + ownedProjectId + "/findings", owner)
                .andExpect(status().isOk());
        perform("GET", "/api/projects/" + ownedProjectId + "/findings", projectMember)
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ helpers

    /** A stranger must not get a 2xx. 403 and 404 are both acceptable; success is not. */
    private void expectDenied(ResultActions actions) throws Exception {
        actions.andExpect(result -> {
            int statusCode = result.getResponse().getStatus();
            if (statusCode >= 200 && statusCode < 300) {
                throw new AssertionError(
                        "endpoint returned " + statusCode + " to a user who does not own the resource");
            }
            if (statusCode == 401) {
                throw new AssertionError(
                        "expected an authorization failure but the caller was treated as anonymous");
            }
        });
    }

    /** Mutation cases use valid JSON so a 400 cannot mask a missing object-level guard. */
    private ResultActions postJson(String path, Cookie cookie, Map<String, ?> body) throws Exception {
        var request = post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (cookie != null) {
            request = request.cookie(cookie);
        }
        return mockMvc.perform(request);
    }

    private void expectForbiddenOrNotFound(ResultActions actions) throws Exception {
        actions.andExpect(result -> {
            int statusCode = result.getResponse().getStatus();
            if (statusCode != 403 && statusCode != 404) {
                throw new AssertionError("expected 403/404 from object-level authorization but got " + statusCode);
            }
        });
    }

    private ResultActions perform(String method, String path, Cookie cookie) throws Exception {
        String url = path;
        String query = null;
        int questionMark = path.indexOf('?');
        if (questionMark >= 0) {
            url = path.substring(0, questionMark);
            query = path.substring(questionMark + 1);
        }
        var request = switch (method) {
            case "POST" -> post(url).contentType(MediaType.APPLICATION_JSON).content("{}");
            case "DELETE" -> delete(url);
            default -> get(url);
        };
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                request = request.param(parts[0], parts.length > 1 ? parts[1] : "");
            }
        }
        if (cookie != null) {
            request = request.cookie(cookie);
        }
        return mockMvc.perform(request);
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

    private Long createProject(Cookie cookie, String name) throws Exception {
        MvcResult project = mockMvc.perform(post("/api/projects")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name, "description", "authz matrix", "defaultBranch", "main"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(project.getResponse().getContentAsString())
                .path("data").path("projectId").asLong();
    }

    private void addMember(Cookie leader, Long projectId, String username, String role) throws Exception {
        mockMvc.perform(post("/api/projects/{id}/members", projectId)
                        .cookie(leader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "role", role))))
                .andExpect(status().isOk());
    }

    private Long findMemberUserId(Cookie cookie, Long projectId, String username) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/members", projectId).cookie(cookie))
                .andExpect(status().isOk())
                .andReturn();
        for (var member : objectMapper.readTree(result.getResponse().getContentAsString()).path("data")) {
            if (username.equals(member.path("username").asText())) {
                return member.path("userId").asLong();
            }
        }
        throw new IllegalStateException("member not found: " + username);
    }

    private Long createFinding() {
        AgentRun run = agentRuns.saveAndFlush(new AgentRun(ownedProjectId, 1L, 1L,
                "matrix-finding-" + System.nanoTime(), "headsha0002"));
        ScmInstallation installation = new ScmInstallation();
        installation.setId(1L);
        installation.setProvider(ScmProviderType.GITHUB);
        installation.setActive(true);
        NormalizedPullRequestEvent event = new NormalizedPullRequestEvent(
                ScmProviderType.GITHUB, "matrix-installation", "demo/repo",
                "https://example.com/demo/repo.git", 1, "matrix finding", "matrix",
                "feature/matrix", "main", "basesha0001", "headsha0002", "opened",
                "matrix-delivery-" + System.nanoTime());
        scmContexts.saveAndFlush(AgentScmContext.from(run.getId(), event, installation));
        return findings.saveAndFlush(new Finding(
                run.getId(), FindingSeverity.HIGH, "security.matrix", "Matrix finding",
                "Finding used by the object-level authorization matrix", "src/Matrix.java",
                1, 1, "Matrix.check", "candidate")).getId();
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
