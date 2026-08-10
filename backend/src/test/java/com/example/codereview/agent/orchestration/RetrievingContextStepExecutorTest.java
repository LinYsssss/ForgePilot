package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.orchestration.steps.RetrievingContextStepExecutor;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.context.ReviewRetrievalQuery;
import com.example.codereview.language.ChangeSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RetrievingContextStepExecutorTest {

    @Test
    void retrievesThroughTypedScopePortAndPersistsCitations() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentAnalysisContextRepository contexts = mock(AgentAnalysisContextRepository.class);
        AgentContextRetriever retriever = mock(AgentContextRetriever.class);
        AgentAnalysisContext stored = new AgentAnalysisContext(1L, "abcdef1");
        stored.repositoryPrepared("workspace://archive", "abcdef0", "{}", "diff");
        stored.changeAnalyzed(mapper.writeValueAsString(new AgentChangeAnalysisCheckpoint(
                "agent-change-analysis-v1",
                new ChangeSet("abcdef0", "abcdef1", List.of(
                        new ChangeSet.FileChange("src/Main.java", ChangeSet.ChangeType.MODIFIED)
                )),
                List.of("java"), List.of()
        )));
        when(contexts.findByAgentRunId(1L)).thenReturn(Optional.of(stored));
        when(contexts.save(any())).thenAnswer(call -> call.getArgument(0));
        when(retriever.retrieve(any())).thenReturn(List.of(new AgentRetrievedContextCheckpoint.Evidence(
                "Close SQL resources", "secure-java#chunk-2", "secure-java", 2,
                "RULE", "abcdef1", 0.88, true
        )));

        AgentStepResult result = new RetrievingContextStepExecutor(contexts, retriever, mapper)
                .execute(new AgentStepExecutionContext(
                        1L, 12L, 2L, 3L, 4L, "abcdef1",
                        AgentRunStatus.RETRIEVING_CONTEXT, 5, 0, "trace", false
                ));

        assertThat(result.nextState()).isEqualTo(AgentRunStatus.VERIFYING_FINDINGS);
        ArgumentCaptor<ReviewRetrievalQuery> scope = ArgumentCaptor.forClass(ReviewRetrievalQuery.class);
        verify(retriever).retrieve(scope.capture());
        assertThat(scope.getValue().projectId()).isEqualTo(2L);
        assertThat(scope.getValue().sourceVersion()).isEqualTo("abcdef1");
        assertThat(scope.getValue().changedPaths()).containsExactly("src/Main.java");
        verify(contexts).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getRetrievedContextJson().contains("secure-java#chunk-2")
                        && saved.getRetrievedContextJson().contains("abcdef1")));
    }
}
