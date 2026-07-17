package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.orchestration.steps.RetrievingContextStepExecutor;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.ai.langchain4j.LangChain4jReviewContentRetriever;
import com.example.codereview.language.ChangeSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RetrievingContextStepExecutorTest {

    @Test
    void retrievesThroughTypedLangChain4jScopeAndPersistsCitations() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentAnalysisContextRepository contexts = mock(AgentAnalysisContextRepository.class);
        LangChain4jReviewContentRetriever retriever = mock(LangChain4jReviewContentRetriever.class);
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
        Metadata metadata = new Metadata()
                .put("citation", "secure-java#chunk-2")
                .put("source_name", "secure-java")
                .put("chunk_index", 2)
                .put("document_type", "RULE")
                .put("source_version", "abcdef1")
                .put("trust", "untrusted");
        when(retriever.retrieve(any())).thenReturn(List.of(Content.from(
                TextSegment.from("Close SQL resources", metadata),
                Map.of(ContentMetadata.SCORE, 0.88)
        )));

        AgentStepResult result = new RetrievingContextStepExecutor(contexts, retriever, mapper)
                .execute(new AgentStepExecutionContext(
                        1L, 12L, 2L, 3L, 4L, "abcdef1",
                        AgentRunStatus.RETRIEVING_CONTEXT, 5, 0, "trace", false
                ));

        assertThat(result.nextState()).isEqualTo(AgentRunStatus.VERIFYING_FINDINGS);
        verify(retriever).retrieve(any());
        verify(contexts).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getRetrievedContextJson().contains("secure-java#chunk-2")
                        && saved.getRetrievedContextJson().contains("abcdef1")));
    }
}
