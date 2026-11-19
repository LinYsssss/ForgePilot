package com.forgepilot.requirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.ai.AiCallContext;
import com.forgepilot.ai.AiGateway;
import com.forgepilot.ai.AiUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Drafting a requirement from a document: it is LEADER-only, it makes one
 * project-scoped provider call that carries the document as untrusted content,
 * it never persists anything, and an answer off the schema is a failure rather
 * than an empty draft. The provider is replaced for the same reason as in
 * {@code RequirementQualityTest}.
 */
@SpringBootTest
class RequirementDraftTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String BODY = "{\"text\":\"借书接口需要幂等。重复请求返回首次结果。\"}";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private AiGateway ai;

    private MockMvc mockMvc;
    private long project;
    private String leader;
    private String developer;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        int n = SEQUENCE.incrementAndGet();
        leader = "draft-leader-" + n;
        developer = "draft-dev-" + n;
        long leaderId = account(leader);
        project = jdbc.queryForObject(
                "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                Long.class, "draft-" + n, leaderId);
        member(leaderId, "LEADER");
        member(account(developer), "DEVELOPER");
    }

    @Test
    void aLeaderGetsACleanedDraftAndNothingIsSaved() throws Exception {
        when(ai.chat(any(), any(), any(), any())).thenReturn("""
                {"title":"  借书接口幂等  ","background":"","description":"防止重复提交。",
                 "acceptanceCriteria":[" 重复请求返回首次结果。 ","   ","可借册数为 0 时返回 409。"]}""");

        mockMvc.perform(post(leader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("借书接口幂等"))
                .andExpect(jsonPath("$.acceptanceCriteria.length()").value(2))
                .andExpect(jsonPath("$.acceptanceCriteria[0]").value("重复请求返回首次结果。"));

        // One call, attributed to the project, with the document after the untrusted marker.
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).chat(prompt.capture(), any(), eq(AiUseCase.REQUIREMENT_QUALITY),
                eq(AiCallContext.ofProject(project)));
        assertThat(prompt.getValue().indexOf("untrusted content"))
                .isPositive().isLessThan(prompt.getValue().indexOf("借书接口需要幂等"));
        assertThat(jdbc.queryForObject("select count(*) from requirement where project_id = ?",
                Long.class, project)).isZero();
    }

    @Test
    void aNonLeaderIsRefusedBeforeAnyProviderCall() throws Exception {
        mockMvc.perform(post(developer)).andExpect(status().isForbidden());
        verify(ai, never()).chat(any(), any(), any(), any());
    }

    @Test
    void anAnswerOffTheSchemaIsAFailureNotAnEmptyDraft() throws Exception {
        when(ai.chat(any(), any(), any(), any())).thenReturn("{\"title\":\"只有标题\"}");

        mockMvc.perform(post(leader))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ai_malformed_result"));
    }

    private org.springframework.test.web.servlet.RequestBuilder post(String username) {
        return MockMvcRequestBuilders.post("/api/projects/" + project + "/requirements/draft")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)
                .with(user(username)).with(spaCsrf());
    }

    private long account(String username) {
        return jdbc.queryForObject(
                "insert into user_account (username, display_name, password_hash) values (?, 'Test User', 'x') returning id",
                Long.class, username);
    }

    private void member(long userId, String role) {
        jdbc.update("with member as (insert into project_member (project_id, user_id) values (?, ?) returning project_id, user_id) insert into project_member_role (project_id, user_id, role) select project_id, user_id, ? from member",
                project, userId, role);
    }
}
