package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.orchestration.steps.PreparingRepositoryStepExecutor;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.tool.AgentToolRegistry;
import com.example.codereview.agent.tool.ToolResult;
import com.example.codereview.pullrequest.PullRequestEntity;
import com.example.codereview.pullrequest.PullRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PreparingRepositoryStepExecutorTest {

    @Test
    void usesFixedSignedSandboxDiffAndPersistsRepositoryProfile() {
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        AgentToolRegistry tools = mock(AgentToolRegistry.class);
        AgentAnalysisContextRepository contexts = mock(AgentAnalysisContextRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        PullRequestEntity pullRequest = new PullRequestEntity(
                2L, 3L, "GITHUB", "42", 42, "title", "author",
                "feature", "main", "abcdef0", "abcdef1"
        );
        when(pullRequests.findById(4L)).thenReturn(Optional.of(pullRequest));
        when(contexts.findByAgentRunId(1L)).thenReturn(Optional.empty());
        org.mockito.Mockito.doReturn(ToolResult.success(Map.of(
                "output", "--- a/src/Main.java\n+++ b/src/Main.java\n--- /dev/null\n+++ b/app.py\n"
        ))).when(tools).execute(any(), any(), any());
        when(contexts.save(any())).thenAnswer(call -> call.getArgument(0));

        AgentStepResult result = new PreparingRepositoryStepExecutor(
                pullRequests, tools, contexts, new RepositoryArchiveRefResolver(), mapper
        ).execute(new AgentStepExecutionContext(
                1L, 10L, 2L, 3L, 4L, "abcdef1",
                AgentRunStatus.PREPARING_REPOSITORY, 1, 0, "trace", false
        ));

        assertThat(result.nextState()).isEqualTo(AgentRunStatus.ANALYZING_CHANGE);
        verify(tools).execute(
                org.mockito.ArgumentMatchers.eq("git.diff"),
                org.mockito.ArgumentMatchers.argThat(input ->
                        input.get("archiveRef").asText().startsWith("workspace://")
                                && !input.has("command") && !input.has("shell")),
                org.mockito.ArgumentMatchers.argThat(toolContext ->
                        toolContext.invocationKey().contains("git.diff:abcdef1"))
        );
        verify(contexts).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getRepositoryProfileJson().contains("JAVA")
                        && saved.getRepositoryProfileJson().contains("PYTHON")));
    }
}
