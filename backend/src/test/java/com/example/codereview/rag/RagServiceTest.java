package com.example.codereview.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.ai.AiCallLogService;
import com.example.codereview.knowledge.KnowledgeChunk;
import com.example.codereview.knowledge.KnowledgeChunkRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock
    private KnowledgeChunkRepository chunks;
    @Mock
    private EmbeddingClient embeddingClient;
    @Mock
    private EmbeddingJson embeddingJson;
    @Mock
    private ObjectProvider<JdbcTemplate> jdbcProvider;
    @Mock
    private AiCallLogService aiCallLogService;

    @Test
    void selectedDocumentsRemainProjectScoped() {
        KnowledgeChunk selected = new KnowledgeChunk(
                10L, 7L, "SECURITY", "selected.md", 0, "selected project content", null);
        when(chunks.findByProjectIdAndDocumentIdIn(7L, List.of(10L))).thenReturn(List.of(selected));
        when(jdbcProvider.getIfAvailable()).thenReturn(null);
        RagService service = new RagService(
                chunks, embeddingClient, embeddingJson, jdbcProvider, aiCallLogService,
                "memory", 5, true, 6000);

        String context = service.buildContext(7L, "ignored", List.of(10L));

        assertThat(context).contains("selected.md", "selected project content");
        assertThat(context).doesNotContain("other project");
        verify(chunks).findByProjectIdAndDocumentIdIn(7L, List.of(10L));
    }

    @Test
    void rejectsSearchAcrossIncompatibleEmbeddingVersions() {
        KnowledgeChunk legacy = new KnowledgeChunk(
                10L,
                7L,
                "SECURITY",
                "legacy.md",
                0,
                "legacy content",
                "[0.1,0.2,0.3]",
                "openai-compatible",
                "embedding-model",
                "embedding-model-v0",
                3
        );
        when(chunks.findByProjectId(7L)).thenReturn(List.of(legacy));
        when(jdbcProvider.getIfAvailable()).thenReturn(null);
        when(embeddingClient.embed("policy")).thenReturn(new EmbeddingClient.EmbeddingResult(
                "openai-compatible",
                "embedding-model",
                "embedding-model-v1",
                3,
                List.of(0.1, 0.2, 0.3)
        ));
        RagService service = new RagService(
                chunks, embeddingClient, embeddingJson, jdbcProvider, aiCallLogService,
                "memory", 5, false, 6000);

        assertThatThrownBy(() -> service.search(7L, "policy", 5))
                .isInstanceOf(EmbeddingReindexRequiredException.class)
                .hasMessageContaining("re-index")
                .hasMessageContaining("embedding-model-v0");
    }

    @Test
    void pgvectorSearchKeepsEmbeddingCompatibilityFilters() {
        KnowledgeChunk compatible = new KnowledgeChunk(
                10L, 7L, "SECURITY", "policy.md", 0, "policy", "[1.0,0.0,0.0]",
                "openai-compatible", "embedding-model", "embedding-model-v1", 3
        );
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        when(chunks.findByProjectId(7L)).thenReturn(List.of(compatible));
        when(jdbcProvider.getIfAvailable()).thenReturn(jdbc);
        when(embeddingClient.embed("policy")).thenReturn(new EmbeddingClient.EmbeddingResult(
                "openai-compatible", "embedding-model", "embedding-model-v1", 3,
                List.of(1.0, 0.0, 0.0)
        ));
        when(embeddingJson.toPgVector(List.of(1.0, 0.0, 0.0))).thenReturn("[1.0,0.0,0.0]");
        RagService service = new RagService(
                chunks, embeddingClient, embeddingJson, jdbcProvider, aiCallLogService,
                "pgvector", 5, false, 6000
        );

        service.search(7L, "policy", 5);

        assertThat(jdbc.sql).contains(
                "kc.project_id = ?",
                "kc.embedding_provider = ?",
                "kc.embedding_model = ?",
                "kc.embedding_version = ?",
                "kc.embedding_dimension = ?"
        );
        assertThat(jdbc.arguments).containsExactly(
                "[1.0,0.0,0.0]",
                7L,
                "openai-compatible",
                "embedding-model",
                "embedding-model-v1",
                3,
                "[1.0,0.0,0.0]",
                5
        );
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] arguments;

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            this.arguments = args;
            return List.of();
        }
    }
}
