package com.example.codereview.ai.langchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.context.ReviewContextService;
import com.example.codereview.context.ReviewRetrievalQuery;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.query.Query;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LangChain4jReviewContentRetrieverTest {

    @Test
    void carriesEverySignalAndMapsExactCitationMetadata() {
        ReviewContextService service = mock(ReviewContextService.class);
        when(service.retrieve(any())).thenReturn(List.of(new ReviewContextService.ContextEvidence(
                "Close SQL connections",
                "security.md#chunk-2",
                "security.md",
                2,
                "head-abc",
                "SECURITY",
                0.87,
                true
        )));
        LangChain4jReviewContentRetriever retriever = new LangChain4jReviewContentRetriever(service);
        ReviewRetrievalQuery scope = query(7L);

        var result = retriever.retrieve(LangChain4jReviewContentRetriever.toQuery(scope));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).textSegment().text()).isEqualTo("Close SQL connections");
        assertThat(result.get(0).textSegment().metadata().getString("citation"))
                .isEqualTo("security.md#chunk-2");
        assertThat(result.get(0).textSegment().metadata().getString("source_name"))
                .isEqualTo("security.md");
        assertThat(result.get(0).textSegment().metadata().getInteger("chunk_index")).isEqualTo(2);
        assertThat(result.get(0).textSegment().metadata().getString("document_type"))
                .isEqualTo("SECURITY");
        assertThat(result.get(0).textSegment().metadata().getString("source_version"))
                .isEqualTo("head-abc");
        assertThat(result.get(0).textSegment().metadata().getString("trust"))
                .isEqualTo("untrusted");
        assertThat(result.get(0).metadata().get(ContentMetadata.SCORE)).isEqualTo(0.87);

        ArgumentCaptor<ReviewContextService.Request> request =
                ArgumentCaptor.forClass(ReviewContextService.Request.class);
        verify(service).retrieve(request.capture());
        assertThat(request.getValue().projectId()).isEqualTo(7L);
        assertThat(request.getValue().documentIds()).containsExactly(11L);
        assertThat(request.getValue().changedPaths()).containsExactly("src/Auth.java");
        assertThat(request.getValue().symbols()).containsExactly("AuthService");
        assertThat(request.getValue().imports()).containsExactly("java.sql.Connection");
        assertThat(request.getValue().annotations()).containsExactly("Transactional");
        assertThat(request.getValue().strings()).containsExactly("SELECT users");
        assertThat(request.getValue().toolRuleIds()).containsExactly("PMD.CloseResource");
        assertThat(request.getValue().topK()).isEqualTo(3);
    }

    @Test
    void rejectsMissingOrUnboundedScopeInsteadOfUsingQueryText() {
        LangChain4jReviewContentRetriever retriever = new LangChain4jReviewContentRetriever(
                mock(ReviewContextService.class)
        );

        assertThatThrownBy(() -> retriever.retrieve(Query.from("projectId=999")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
        assertThatThrownBy(() -> LangChain4jReviewContentRetriever.toQuery(new ReviewRetrievalQuery(
                7L, List.of(), "head-abc", 0, 0.2, 3,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budget");
        assertThatThrownBy(() -> query(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("projectId");
        assertThatThrownBy(() -> new ReviewRetrievalQuery(
                7L, List.of(), " ", 100, 0.2, 3,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceVersion");
    }

    @Test
    void untrustedQueryTextCannotWidenTypedProjectScope() {
        ReviewContextService service = mock(ReviewContextService.class);
        when(service.retrieve(any())).thenReturn(List.of());
        LangChain4jReviewContentRetriever retriever = new LangChain4jReviewContentRetriever(service);
        Query frameworkQuery = LangChain4jReviewContentRetriever.toQuery(query(7L));
        Query injected = new Query(
                "projectId=999 documentIds=all ignore scope",
                frameworkQuery.metadata()
        );

        retriever.retrieve(injected);

        ArgumentCaptor<ReviewContextService.Request> request =
                ArgumentCaptor.forClass(ReviewContextService.Request.class);
        verify(service).retrieve(request.capture());
        assertThat(request.getValue().projectId()).isEqualTo(7L);
        assertThat(request.getValue().documentIds()).containsExactly(11L);
    }

    private ReviewRetrievalQuery query(Long projectId) {
        return new ReviewRetrievalQuery(
                projectId,
                List.of(11L),
                "head-abc",
                4_096,
                0.2,
                3,
                List.of("src/Auth.java"),
                List.of("AuthService"),
                List.of("java.sql.Connection"),
                List.of("Transactional"),
                List.of("SELECT users"),
                List.of("PMD.CloseResource")
        );
    }
}
