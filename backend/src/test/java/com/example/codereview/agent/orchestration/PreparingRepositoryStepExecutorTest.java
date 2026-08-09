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
        AgentScmContextRepository scmContexts = mock(AgentScmContextRepository.class);
        AgentToolRegistry tools = mock(AgentToolRegistry.class);
        AgentAnalysisContextRepository contexts = mock(AgentAnalysisContextRepository.class);
        WorkspaceArchiveService workspaceArchives = mock(WorkspaceArchiveService.class);
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
        // 精确入参存根兼作装配断言:归档必须在派发前真实产出(repositoryId=3、runId=1、base→head)。
        when(workspaceArchives.produceForAgentRun(3L, 1L, "abcdef0", "abcdef1"))
                .thenReturn("agent-run-1-abcdef1.tar");

        AgentStepResult result = new PreparingRepositoryStepExecutor(
                pullRequests, scmContexts, tools, contexts, workspaceArchives, mapper
        ).execute(new AgentStepExecutionContext(
                1L, 10L, 2L, 3L, 4L, "abcdef1",
                AgentRunStatus.PREPARING_REPOSITORY, 1, 0, "trace", false
        ));

        assertThat(result.nextState()).isEqualTo(AgentRunStatus.ANALYZING_CHANGE);
        verify(tools).execute(
                org.mockito.ArgumentMatchers.eq("git.diff"),
                // 钉住线格式字面量:旧断言 startsWith("workspace://") 恰恰把断链格式当成了预期。
                org.mockito.ArgumentMatchers.argThat(input ->
                        input.get("archiveRef").asText().equals("agent-run-1-abcdef1.tar")
                                && !input.has("command") && !input.has("shell")),
                org.mockito.ArgumentMatchers.argThat(toolContext ->
                        toolContext.invocationKey().contains("git.diff:abcdef1"))
        );
        verify(contexts).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getRepositoryProfileJson().contains("JAVA")
                        && saved.getRepositoryProfileJson().contains("PYTHON")));
    }

    /** webhook 触发的 Run 没有 PullRequestEntity(pullRequestId=null),base/head 取自 SCM 上下文。 */
    @Test
    void resolvesBaseHeadFromScmContextWhenRunHasNoPullRequestEntity() {
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        AgentScmContextRepository scmContexts = mock(AgentScmContextRepository.class);
        AgentToolRegistry tools = mock(AgentToolRegistry.class);
        AgentAnalysisContextRepository contexts = mock(AgentAnalysisContextRepository.class);
        WorkspaceArchiveService workspaceArchives = mock(WorkspaceArchiveService.class);
        ObjectMapper mapper = new ObjectMapper();
        AgentScmContext scm = mock(AgentScmContext.class);
        when(scm.getBaseSha()).thenReturn("abcdef0");
        when(scm.getHeadSha()).thenReturn("abcdef1");
        when(scmContexts.findByAgentRunId(1L)).thenReturn(Optional.of(scm));
        when(contexts.findByAgentRunId(1L)).thenReturn(Optional.empty());
        org.mockito.Mockito.doReturn(ToolResult.success(Map.of(
                "output", "--- a/src/Main.java\n+++ b/src/Main.java\n"
        ))).when(tools).execute(any(), any(), any());
        when(contexts.save(any())).thenAnswer(call -> call.getArgument(0));
        when(workspaceArchives.produceForAgentRun(3L, 1L, "abcdef0", "abcdef1"))
                .thenReturn("agent-run-1-abcdef1.tar");

        AgentStepResult result = new PreparingRepositoryStepExecutor(
                pullRequests, scmContexts, tools, contexts, workspaceArchives, mapper
        ).execute(new AgentStepExecutionContext(
                1L, 10L, 2L, 3L, null, "abcdef1",
                AgentRunStatus.PREPARING_REPOSITORY, 1, 0, "trace", false
        ));

        assertThat(result.nextState()).isEqualTo(AgentRunStatus.ANALYZING_CHANGE);
        verify(workspaceArchives).produceForAgentRun(3L, 1L, "abcdef0", "abcdef1");
        org.mockito.Mockito.verifyNoInteractions(pullRequests);
    }
}
