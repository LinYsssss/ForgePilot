package com.example.codereview.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.codereview.knowledge.KnowledgeDtos.SearchMatch;
import com.example.codereview.rag.RagService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewContextServiceTest {

    @Mock
    private RagService ragService;

    @Test
    void buildsQueriesFromEveryReviewSignal() {
        ReviewContextService.Request request = new ReviewContextService.Request(
                7L, List.of(11L), "abc123", 4096, 0.2,
                List.of("src/Auth.java"), List.of("AuthService"), List.of("java.sql.Connection"),
                List.of("Transactional"), List.of("SELECT * FROM users"), List.of("PMD.CloseResource"));

        assertThat(ReviewContextService.buildQueries(request)).containsExactly(
                "path:src/Auth.java", "symbol:AuthService", "import:java.sql.Connection",
                "annotation:Transactional", "string:SELECT * FROM users", "rule:PMD.CloseResource");
    }

    @Test
    void ranksFiltersDeduplicatesAndEnforcesUtf8ByteBudget() {
        SearchMatch preferred = new SearchMatch(1L, "security.md", "SECURITY", 2, 0.8,
                "AuthService must close SQL connections");
        SearchMatch duplicate = new SearchMatch(2L, "copy.md", "GENERAL", 0, 0.99,
                "AuthService must close SQL connections");
        SearchMatch belowThreshold = new SearchMatch(3L, "misc.md", "GENERAL", 0, 0.05, "unrelated");
        when(ragService.search(7L, "symbol:AuthService", 24, List.of(11L)))
                .thenReturn(List.of(duplicate, belowThreshold, preferred));
        ReviewContextService service = new ReviewContextService(ragService, new HybridContextRanker());
        ReviewContextService.Request request = new ReviewContextService.Request(
                7L, List.of(11L), "abc123", 180, 0.2,
                List.of(), List.of("AuthService"), List.of(), List.of(), List.of(), List.of());

        List<ReviewContextService.ContextEvidence> result = service.retrieve(request);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).isEqualTo(preferred.content());
        assertThat(result.get(0).untrusted()).isTrue();
        assertThat(result.get(0).sourceVersion()).isEqualTo("abc123");
        assertThat(result.get(0).reference()).isEqualTo("security.md#chunk-2");
        assertThat(result.get(0).content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(180);
    }

    @Test
    void hybridRankerCombinesVectorLexicalSymbolAndDocumentTypeSignals() {
        HybridContextRanker ranker = new HybridContextRanker();
        SearchMatch securitySymbol = new SearchMatch(1L, "security.md", "SECURITY", 0, 0.55,
                "AuthService connection policy");
        SearchMatch genericVector = new SearchMatch(2L, "notes.md", "GENERAL", 0, 0.95,
                "generic connection notes");

        assertThat(ranker.rank(List.of(genericVector, securitySymbol), List.of("AuthService"),
                List.of("connection"))).containsExactly(securitySymbol, genericVector);
    }

    @Test
    void hybridRankerRetainsDocumentedWeightsExactly() {
        HybridContextRanker ranker = new HybridContextRanker();
        SearchMatch match = new SearchMatch(1L, "security.md", "SECURITY", 0, 0.5,
                "AuthService connection policy");

        double score = ranker.score(
                match,
                List.of("AuthService", "MissingSymbol"),
                List.of("connection", "missing-term")
        );

        assertThat(score).isEqualTo(
                0.5 * 0.40
                        + 0.5 * 0.25
                        + 0.5 * 0.20
                        + 1.0 * 0.15
        );
    }

    @Test
    void appliesStableTopKAfterHybridRankingAndDeduplication() {
        SearchMatch first = new SearchMatch(1L, "security.md", "SECURITY", 0, 0.9,
                "AuthService policy one");
        SearchMatch second = new SearchMatch(2L, "design.md", "DESIGN", 0, 0.8,
                "AuthService policy two");
        when(ragService.search(7L, "symbol:AuthService", 24, List.of()))
                .thenReturn(List.of(second, first));
        ReviewContextService service = new ReviewContextService(ragService, new HybridContextRanker());
        ReviewContextService.Request request = new ReviewContextService.Request(
                7L, List.of(), "abc123", 4096, 0.0, 1,
                List.of(), List.of("AuthService"), List.of(), List.of(), List.of(), List.of());

        assertThat(service.retrieve(request))
                .extracting(ReviewContextService.ContextEvidence::reference)
                .containsExactly("security.md#chunk-0");
    }
}
