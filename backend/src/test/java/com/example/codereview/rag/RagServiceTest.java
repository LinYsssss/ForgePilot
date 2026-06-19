package com.example.codereview.rag;

import static org.assertj.core.api.Assertions.assertThat;
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
}
