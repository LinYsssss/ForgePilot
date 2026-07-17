package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.orchestration.steps.AnalyzingChangeStepExecutor;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.language.LanguagePlugin;
import com.example.codereview.language.LanguagePluginSelector;
import com.example.codereview.language.RepositoryProfile;
import com.example.codereview.language.java.JavaAnalysisPlugin;
import com.example.codereview.language.javascript.JavascriptAnalysisPlugin;
import com.example.codereview.language.python.PythonAnalysisPlugin;
import com.example.codereview.language.LanguageToolFindingNormalizer;
import com.example.codereview.agent.tool.AgentToolRegistry;
import com.example.codereview.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AnalyzingChangeStepExecutorTest {

    @Test
    void deterministicallySelectsAllPluginsForMixedLanguageChange() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentAnalysisContextRepository contexts = mock(AgentAnalysisContextRepository.class);
        AgentAnalysisContext stored = new AgentAnalysisContext(1L, "abcdef1");
        String diff = """
                --- a/src/Main.java
                +++ b/src/Main.java
                --- /dev/null
                +++ b/app.py
                --- /dev/null
                +++ b/web/app.ts
                """;
        stored.repositoryPrepared(
                "workspace://archive", "abcdef0", mapper.writeValueAsString(RepositoryProfile.fromPaths(
                        List.of("src/Main.java", "app.py", "web/app.ts")
                )), diff
        );
        when(contexts.findByAgentRunId(1L)).thenReturn(Optional.of(stored));
        when(contexts.save(any())).thenAnswer(call -> call.getArgument(0));
        String image = "analysis@sha256:" + "0".repeat(64);
        List<LanguagePlugin> plugins = List.of(
                new PythonAnalysisPlugin(image, "v1"),
                new JavascriptAnalysisPlugin(image, "v1"),
                new JavaAnalysisPlugin(image, "v1")
        );

        AgentStepResult result = new AnalyzingChangeStepExecutor(
                contexts, new LanguagePluginSelector(plugins), mapper
        ).execute(new AgentStepExecutionContext(
                1L, 11L, 2L, 3L, 4L, "abcdef1",
                AgentRunStatus.ANALYZING_CHANGE, 2, 0, "trace", false
        ));

        assertThat(result.nextState()).isEqualTo(AgentRunStatus.PLANNING);
        assertThat(result.output().get("plugins").toString())
                .containsSubsequence("java", "javascript", "python");
        verify(contexts).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getChangeSetJson().contains("agent-change-analysis-v1")));
    }

    @Test
    void executesPluginDeclaredFixedCommandAndNormalizesStaticEvidence() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentAnalysisContextRepository contexts = mock(AgentAnalysisContextRepository.class);
        AgentToolRegistry tools = mock(AgentToolRegistry.class);
        AgentAnalysisContext stored = new AgentAnalysisContext(2L, "abcdef2");
        stored.repositoryPrepared(
                "workspace://archive", "abcdef0",
                mapper.writeValueAsString(RepositoryProfile.fromPaths(List.of("app.py", "pyproject.toml"))),
                "--- a/app.py\n+++ b/app.py\n"
        );
        when(contexts.findByAgentRunId(2L)).thenReturn(Optional.of(stored));
        when(contexts.save(any())).thenAnswer(call -> call.getArgument(0));
        org.mockito.Mockito.doReturn(ToolResult.success(java.util.Map.of(
                "output", "[{\"code\":\"S101\",\"message\":\"assert used\","
                        + "\"filename\":\"app.py\",\"location\":{\"row\":3},"
                        + "\"end_location\":{\"row\":3}}]"
        ))).when(tools).execute(any(), any(), any());
        String image = "analysis@sha256:" + "0".repeat(64);

        new AnalyzingChangeStepExecutor(
                contexts,
                new LanguagePluginSelector(List.of(new PythonAnalysisPlugin(image, "v1"))),
                mapper,
                tools,
                new LanguageToolFindingNormalizer()
        ).execute(new AgentStepExecutionContext(
                2L, 12L, 2L, 3L, 4L, "abcdef2",
                AgentRunStatus.ANALYZING_CHANGE, 2, 0, "trace", false
        ));

        verify(tools, org.mockito.Mockito.atLeastOnce()).execute(
                org.mockito.ArgumentMatchers.eq("language.command"),
                org.mockito.ArgumentMatchers.argThat(input ->
                        input.get("commandId").asText().startsWith("python.")
                                && !input.has("command") && !input.has("shell")),
                any()
        );
        assertThat(stored.getChangeSetJson()).contains("S101", "STATIC_ANALYZER", "abcdef2");
    }
}
