package com.example.codereview.agent.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.run.AgentStep;
import com.example.codereview.agent.run.AgentStepRepository;
import com.example.codereview.agent.run.AgentStepStatus;
import com.example.codereview.auth.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "app.security.token-secret=test-secret",
        "app.security.token-encrypt-key=test-encrypt-key",
        "app.git.allow-local-path=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false"
})
@AutoConfigureMockMvc
class AgentRunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AgentRunRepository runs;

    @Autowired
    private AgentStepRepository steps;

    @Test
    void rejectsUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/agent-runs/{id}", 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsRunOwnedByAnotherUser() throws Exception {
        String owner = seedAndLogin("owner_" + System.nanoTime());
        Long projectId = createProject(owner, "owned project");
        Long runId = seedRun(projectId, AgentRunStatus.EXECUTING_TOOLS, 1);

        String stranger = seedAndLogin("stranger_" + System.nanoTime());
        mockMvc.perform(get("/api/agent-runs/{id}", runId)
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void returnsPersistedRunDetail() throws Exception {
        String token = seedAndLogin("detail_" + System.nanoTime());
        Long projectId = createProject(token, "detail project");
        Long runId = seedRun(projectId, AgentRunStatus.PLANNING, 2);

        mockMvc.perform(get("/api/agent-runs/{id}", runId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(runId))
                .andExpect(jsonPath("$.data.status").value("PLANNING"))
                .andExpect(jsonPath("$.data.currentStepSequence").value(2))
                .andExpect(jsonPath("$.data.terminal").value(false));
    }

    @Test
    void returnsTimelineOrderedBySequence() throws Exception {
        String token = seedAndLogin("timeline_" + System.nanoTime());
        Long projectId = createProject(token, "timeline project");
        Long runId = seedRun(projectId, AgentRunStatus.EXECUTING_TOOLS, 3);
        // Persist out of order to prove the query orders by sequenceNo.
        persistStep(runId, 3, AgentRunStatus.EXECUTING_TOOLS, AgentStepStatus.PENDING);
        persistStep(runId, 1, AgentRunStatus.PREPARING_REPOSITORY, AgentStepStatus.SUCCEEDED);
        persistStep(runId, 2, AgentRunStatus.ANALYZING_CHANGE, AgentStepStatus.SUCCEEDED);

        mockMvc.perform(get("/api/agent-runs/{id}/timeline", runId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run.id").value(runId))
                .andExpect(jsonPath("$.data.steps.length()").value(3))
                .andExpect(jsonPath("$.data.steps[0].sequenceNo").value(1))
                .andExpect(jsonPath("$.data.steps[1].sequenceNo").value(2))
                .andExpect(jsonPath("$.data.steps[2].sequenceNo").value(3));
    }

    @Test
    void cancelMovesActiveRunToCanceledAndCancelsCurrentStep() throws Exception {
        String token = seedAndLogin("cancel_" + System.nanoTime());
        Long projectId = createProject(token, "cancel project");
        Long runId = seedRun(projectId, AgentRunStatus.ANALYZING_CHANGE, 1);
        persistStep(runId, 1, AgentRunStatus.ANALYZING_CHANGE, AgentStepStatus.RUNNING);

        mockMvc.perform(post("/api/agent-runs/{id}/cancel", runId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.cancellationRequested").value(true))
                .andExpect(jsonPath("$.data.terminal").value(true));

        AgentRun persisted = runs.findById(runId).orElseThrow();
        Assertions.assertThat(persisted.getStatus()).isEqualTo(AgentRunStatus.CANCELED);
        AgentStep step = steps.findByAgentRunIdAndSequenceNo(runId, 1).orElseThrow();
        Assertions.assertThat(step.getStatus()).isEqualTo(AgentStepStatus.CANCELED);
    }

    @Test
    void cancelIsRejectedForCompletedRun() throws Exception {
        String token = seedAndLogin("done_" + System.nanoTime());
        Long projectId = createProject(token, "done project");
        Long runId = seedRun(projectId, AgentRunStatus.COMPLETED, 5);

        mockMvc.perform(post("/api/agent-runs/{id}/cancel", runId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void retryReopensFailedRunForAnotherAttempt() throws Exception {
        String token = seedAndLogin("retry_" + System.nanoTime());
        Long projectId = createProject(token, "retry project");
        Long runId = seedRun(projectId, AgentRunStatus.FAILED, 1);
        persistStep(runId, 1, AgentRunStatus.EXECUTING_TOOLS, AgentStepStatus.FAILED);

        mockMvc.perform(post("/api/agent-runs/{id}/retry", runId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETRY_WAIT"))
                .andExpect(jsonPath("$.data.cancellationRequested").value(false));

        AgentRun persisted = runs.findById(runId).orElseThrow();
        Assertions.assertThat(persisted.getStatus()).isEqualTo(AgentRunStatus.RETRY_WAIT);
    }

    @Test
    void retryIsRejectedForActiveRun() throws Exception {
        String token = seedAndLogin("retryactive_" + System.nanoTime());
        Long projectId = createProject(token, "retry active project");
        Long runId = seedRun(projectId, AgentRunStatus.EXECUTING_TOOLS, 1);

        mockMvc.perform(post("/api/agent-runs/{id}/retry", runId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void eventsStreamReplaysPersistedTailAfterLastEventId() throws Exception {
        String token = seedAndLogin("sse_" + System.nanoTime());
        Long projectId = createProject(token, "sse project");
        // Terminal run: the emitter replays the persisted tail then completes, so the async dispatch
        // yields a finished response we can read.
        Long runId = seedRun(projectId, AgentRunStatus.COMPLETED, 3);
        persistStep(runId, 1, AgentRunStatus.PREPARING_REPOSITORY, AgentStepStatus.SUCCEEDED);
        persistStep(runId, 2, AgentRunStatus.ANALYZING_CHANGE, AgentStepStatus.SUCCEEDED);
        persistStep(runId, 3, AgentRunStatus.PLANNING, AgentStepStatus.SUCCEEDED);

        MvcResult started = mockMvc.perform(get("/api/agent-runs/{id}/events", runId)
                        .header("Authorization", "Bearer " + token)
                        .header("Last-Event-ID", "1")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Resume from Last-Event-ID=1: only steps 2 and 3 replay, tagged with their sequence as the id.
        Assertions.assertThat(body).contains("id:2");
        Assertions.assertThat(body).contains("id:3");
        Assertions.assertThat(body).doesNotContain("id:1");
    }

    @Test
    void eventsStreamRejectsUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/agent-runs/{id}/events", 1L)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers -----------------------------------------------------------------

    private Long seedRun(Long projectId, AgentRunStatus status, int currentStepSequence) {
        AgentRun run = new AgentRun(projectId, 1L, null, "trigger-" + System.nanoTime(), "headsha0001");
        if (status != AgentRunStatus.RECEIVED) {
            run.advanceTo(status, currentStepSequence);
        }
        return runs.save(run).getId();
    }

    private void persistStep(Long runId, int sequenceNo, AgentRunStatus stepType, AgentStepStatus target) {
        AgentStep step = AgentStep.pending(runId, sequenceNo, stepType);
        switch (target) {
            case PENDING -> {
            }
            case RUNNING -> step.start(0);
            case SUCCEEDED -> {
                step.start(0);
                step.succeed("ok");
            }
            case FAILED -> {
                step.start(0);
                step.fail("boom");
            }
            default -> throw new IllegalArgumentException("Unsupported seed status " + target);
        }
        steps.save(step);
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
        return objectMapper.readTree(login.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    private Long createProject(String token, String name) throws Exception {
        MvcResult project = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "description", "agent timeline test",
                                "defaultBranch", "main"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(project.getResponse().getContentAsString())
                .path("data").path("projectId").asLong();
    }
}
