package com.example.codereview.finding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.auth.AuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 反馈闭环两个端点的 HTTP 层行为:校验矩阵(401/403/404/400)、upsert 幂等,
 * 以及导出格式钉死——JSON Lines 每行的字段集是 r8 回灌工具的消费契约,这里防漂移。
 */
@SpringBootTest(properties = {
        "app.security.token-secret=test-secret",
        "app.security.token-encrypt-key=test-encrypt-key",
        "app.git.allow-local-path=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false",
        // 用例会反复 seedAndLogin,放开登录限流(生产默认 8 次/分钟)
        "app.ratelimit.login-limit=1000"
})
@AutoConfigureMockMvc
class ReviewFeedbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AgentRunRepository runs;

    @Autowired
    private FindingRepository findings;

    @Autowired
    private ReviewFeedbackRepository feedbacks;

    private ListAppender<ILoggingEvent> auditAppender;
    private ch.qos.logback.classic.Logger auditLogger;

    @BeforeEach
    void attachAuditAppender() {
        auditLogger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("security.audit");
        auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);
    }

    @AfterEach
    void cleanUp() {
        auditLogger.detachAppender(auditAppender);
        // 反馈表跨用例共享同一个 H2 上下文,导出类用例要求行集可预期,用完即清
        feedbacks.deleteAll();
    }

    // ------------------------------------------------------------ 矩阵:401 / 404 / 403

    @Test
    void anonymousCallersAreRejectedOnBothEndpoints() throws Exception {
        mockMvc.perform(post("/api/agent-runs/{runId}/feedback", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missReportBody("a.java", 1, "漏了")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/feedback/export"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownRunIs404() throws Exception {
        String token = seedAndLogin("fb_norun_" + System.nanoTime(), "DEVELOPER");
        mockMvc.perform(post("/api/agent-runs/{runId}/feedback", 987654321L)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missReportBody("a.java", 1, "漏了")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void strangerCannotAttachFeedbackToAForeignRun() throws Exception {
        String owner = seedAndLogin("fb_owner_" + System.nanoTime(), "DEVELOPER");
        Long projectId = createProject(owner, "反馈项目");
        Long runId = seedRun(projectId);

        String stranger = seedAndLogin("fb_stranger_" + System.nanoTime(), "DEVELOPER");
        mockMvc.perform(post("/api/agent-runs/{runId}/feedback", runId)
                        .header("Authorization", "Bearer " + stranger)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missReportBody("a.java", 1, "漏了")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    // ------------------------------------------------------------ 矩阵:400

    @Test
    void invalidTypeFieldCombinationsAre400() throws Exception {
        String token = seedAndLogin("fb_badreq_" + System.nanoTime(), "DEVELOPER");
        Long projectId = createProject(token, "组合校验项目");
        Long runId = seedRun(projectId);

        // 误报/确认缺 findingId
        expect400(token, runId, body(Map.of("type", "FINDING_CONFIRMED", "note", "确认")));
        // 漏报缺 path
        expect400(token, runId, body(Map.of("type", "MISS_REPORT", "note", "漏了")));
        // 漏报不许挂 findingId
        expect400(token, runId, body(Map.of("type", "MISS_REPORT", "findingId", 1, "path", "a.java")));
        // 未知 type 由反序列化直接拒掉
        expect400(token, runId, body(Map.of("type", "SOMETHING_ELSE", "path", "a.java")));
        // note 超过 2000 上限
        expect400(token, runId, body(Map.of("type", "MISS_REPORT", "path", "a.java", "note", "长".repeat(2001))));
        // line 必须为正数
        expect400(token, runId, body(Map.of("type", "MISS_REPORT", "path", "a.java", "line", 0)));
    }

    @Test
    void findingOfAnotherRunIs404() throws Exception {
        String token = seedAndLogin("fb_foreignfinding_" + System.nanoTime(), "DEVELOPER");
        Long projectId = createProject(token, "跨 run 项目");
        Long runId = seedRun(projectId);
        Long otherRunId = seedRun(projectId);
        Long foreignFindingId = seedFinding(otherRunId);

        mockMvc.perform(post("/api/agent-runs/{runId}/feedback", runId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("type", "FINDING_CONFIRMED", "findingId", foreignFindingId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ------------------------------------------------------------ 写入 / upsert / 审计

    @Test
    void submitPersistsEchoesAndLeavesAnAuditTrail() throws Exception {
        String token = seedAndLogin("fb_submit_" + System.nanoTime(), "DEVELOPER");
        Long projectId = createProject(token, "提交项目");
        Long runId = seedRun(projectId);
        Long findingId = seedFinding(runId);

        mockMvc.perform(post("/api/agent-runs/{runId}/feedback", runId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("type", "FINDING_FALSE_POSITIVE",
                                "findingId", findingId, "note", "参数已在上游校验"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.runId").value(runId))
                .andExpect(jsonPath("$.data.findingId").value(findingId))
                .andExpect(jsonPath("$.data.type").value("FINDING_FALSE_POSITIVE"))
                .andExpect(jsonPath("$.data.note").value("参数已在上游校验"));

        assertThat(feedbacks.count()).isEqualTo(1);
        // 审计流里必须能回放这次写入(专有事件,而非仅有兜底过滤器的 API_POST)
        assertThat(auditAppender.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("action=REVIEW_FEEDBACK_SUBMIT", "outcome=SUCCESS", "resource=reviewFeedback:"));
    }

    @Test
    void resubmittingSameFindingAndTypeUpsertsToTheLatest() throws Exception {
        String token = seedAndLogin("fb_upsert_" + System.nanoTime(), "DEVELOPER");
        Long projectId = createProject(token, "幂等项目");
        Long runId = seedRun(projectId);
        Long findingId = seedFinding(runId);

        Long firstId = submitOk(token, runId,
                Map.of("type", "FINDING_CONFIRMED", "findingId", findingId, "note", "第一版结论"));
        Long secondId = submitOk(token, runId,
                Map.of("type", "FINDING_CONFIRMED", "findingId", findingId, "note", "修订后的结论"));

        // 不 409、不堆行:同 reporter+finding+type 覆盖同一条记录
        assertThat(secondId).isEqualTo(firstId);
        assertThat(feedbacks.count()).isEqualTo(1);
        assertThat(feedbacks.findById(firstId).orElseThrow().getNote()).isEqualTo("修订后的结论");
    }

    @Test
    void missReportsAtDifferentLocationsStayAsSeparateRecords() throws Exception {
        String token = seedAndLogin("fb_miss_" + System.nanoTime(), "DEVELOPER");
        Long projectId = createProject(token, "漏报项目");
        Long runId = seedRun(projectId);

        submitOk(token, runId, Map.of("type", "MISS_REPORT", "path", "src/A.java", "line", 10, "note", "第一处"));
        submitOk(token, runId, Map.of("type", "MISS_REPORT", "path", "src/A.java", "line", 20, "note", "第二处"));
        // 重复补录第一处:覆盖而不是第三行
        submitOk(token, runId, Map.of("type", "MISS_REPORT", "path", "src/A.java", "line", 10, "note", "第一处更新"));

        assertThat(feedbacks.count()).isEqualTo(2);
    }

    // ------------------------------------------------------------ 导出

    @Test
    void exportIsForbiddenForNonAdmins() throws Exception {
        String token = seedAndLogin("fb_dev_" + System.nanoTime(), "DEVELOPER");
        mockMvc.perform(get("/api/feedback/export")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /** 导出行格式钉死:字段集、JSON Lines 结构、行序。r8 回灌工具按这些字段名逐行消费。 */
    @Test
    void exportEmitsOneJsonObjectPerLineWithThePinnedFieldSet() throws Exception {
        String admin = seedAndLogin("fb_admin_" + System.nanoTime(), "ADMIN");
        feedbacks.save(new ReviewFeedback(70L, 71L, ReviewFeedbackType.FINDING_CONFIRMED,
                null, null, null, "确认", 7L));
        feedbacks.save(new ReviewFeedback(70L, null, ReviewFeedbackType.MISS_REPORT,
                "src/Pay.java", 33, "security", "对账遗漏", 7L));

        MvcResult result = mockMvc.perform(get("/api/feedback/export")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("application/x-ndjson");
        String[] lines = result.getResponse().getContentAsString().split("\n");
        assertThat(lines).hasSize(2);
        for (String line : lines) {
            JsonNode node = objectMapper.readTree(line);
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            assertThat(names).containsExactlyInAnyOrder(
                    "id", "runId", "findingId", "type", "path", "line", "category", "note", "reporter", "createdAt");
        }
        JsonNode missLine = objectMapper.readTree(lines[1]);
        assertThat(missLine.get("type").asText()).isEqualTo("MISS_REPORT");
        assertThat(missLine.get("path").asText()).isEqualTo("src/Pay.java");
        assertThat(missLine.get("line").asInt()).isEqualTo(33);
        assertThat(missLine.get("reporter").asLong()).isEqualTo(7L);
        assertThat(missLine.get("findingId").isNull()).isTrue();
        // createdAt 必须是 ISO-8601 字符串(r8 侧要按时间截取),不能退化成秒数
        assertThat(Instant.parse(missLine.get("createdAt").asText())).isNotNull();
    }

    @Test
    void exportSinceFiltersIncrementallyAndRejectsGarbage() throws Exception {
        String admin = seedAndLogin("fb_since_" + System.nanoTime(), "ADMIN");
        ReviewFeedback old = feedbacks.save(new ReviewFeedback(80L, null, ReviewFeedbackType.MISS_REPORT,
                "src/Old.java", 1, null, "旧反馈", 8L));
        backdate(old, Instant.parse("2026-01-01T00:00:00Z"));
        feedbacks.save(old);
        feedbacks.save(new ReviewFeedback(80L, null, ReviewFeedbackType.MISS_REPORT,
                "src/New.java", 2, null, "新反馈", 8L));

        String content = mockMvc.perform(get("/api/feedback/export")
                        .header("Authorization", "Bearer " + admin)
                        .param("since", "2026-06-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(content.strip().lines()).hasSize(1);
        assertThat(content).contains("src/New.java").doesNotContain("src/Old.java");

        mockMvc.perform(get("/api/feedback/export")
                        .header("Authorization", "Bearer " + admin)
                        .param("since", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * 钉住解释性决策(设计未详述、实现补全):upsert 覆盖旧内容时刷新 createdAt,
     * 因此被更新过的旧记录必须重新落进 {@code ?since=} 增量窗口——r8 增量导出不漏更新。
     * 若有人把 createdAt 改成"只记首次提交",本用例第二次导出会变空而立即失败。
     */
    @Test
    void upsertRefreshingCreatedAtKeepsIncrementalExportFromMissingUpdates() throws Exception {
        String admin = seedAndLogin("fb_upsertsince_" + System.nanoTime(), "ADMIN");
        Long projectId = createProject(admin, "增量幂等项目");
        Long runId = seedRun(projectId);
        Long feedbackId = submitOk(admin, runId,
                Map.of("type", "MISS_REPORT", "path", "src/Gap.java", "line", 5, "note", "初版"));

        // 回拨到远早于 since 的时刻,先证明这条记录确实被增量窗口排除
        ReviewFeedback stale = feedbacks.findById(feedbackId).orElseThrow();
        backdate(stale, Instant.parse("2026-01-01T00:00:00Z"));
        feedbacks.save(stale);
        String beforeUpsert = exportSince(admin, "2026-06-01T00:00:00Z");
        assertThat(beforeUpsert).doesNotContain("src/Gap.java");

        // 同一位置重复补录:覆盖同一条记录并刷新 createdAt
        Long upsertedId = submitOk(admin, runId,
                Map.of("type", "MISS_REPORT", "path", "src/Gap.java", "line", 5, "note", "修订"));
        assertThat(upsertedId).isEqualTo(feedbackId);

        String afterUpsert = exportSince(admin, "2026-06-01T00:00:00Z");
        assertThat(afterUpsert.strip().lines()).hasSize(1);
        assertThat(afterUpsert).contains("src/Gap.java").contains("修订");
    }

    // ------------------------------------------------------------ helpers

    private void expect400(String token, Long runId, String payload) throws Exception {
        mockMvc.perform(post("/api/agent-runs/{runId}/feedback", runId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    private Long submitOk(String token, Long runId, Map<String, Object> payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/agent-runs/{runId}/feedback", runId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    /** 增量导出取回体:断言 200 后直接给正文,让用例只关心「窗口里有没有这条」。 */
    private String exportSince(String token, String since) throws Exception {
        return mockMvc.perform(get("/api/feedback/export")
                        .header("Authorization", "Bearer " + token)
                        .param("since", since))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String body(Map<String, Object> payload) throws Exception {
        return objectMapper.writeValueAsString(payload);
    }

    private String missReportBody(String path, int line, String note) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "MISS_REPORT");
        payload.put("path", path);
        payload.put("line", line);
        payload.put("note", note);
        return body(payload);
    }

    private Long seedRun(Long projectId) {
        return runs.save(new AgentRun(projectId, 1L, null,
                "feedback-trigger-" + System.nanoTime(), "headsha0001")).getId();
    }

    private Long seedFinding(Long runId) {
        return findings.save(new Finding(runId, FindingSeverity.HIGH, "security", "越权发货",
                "forceShip 未校验支付状态", "src/OrderService.java", 21, 28, "forceShip", "verified")).getId();
    }

    /** createdAt 由实体自管,构造导出增量场景只能反射回拨。 */
    private static void backdate(ReviewFeedback feedback, Instant createdAt) {
        try {
            Field field = ReviewFeedback.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(feedback, createdAt);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String seedAndLogin(String username, String role) throws Exception {
        authService.createUser(username, "123456", "Tester", role);
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "123456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        jakarta.servlet.http.Cookie authCookie = login.getResponse().getCookie("reposage_auth");
        assertThat(authCookie).isNotNull();
        return authCookie.getValue();
    }

    private Long createProject(String token, String name) throws Exception {
        MvcResult project = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "description", "feedback test",
                                "defaultBranch", "main"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(project.getResponse().getContentAsString())
                .path("data").path("projectId").asLong();
    }
}
