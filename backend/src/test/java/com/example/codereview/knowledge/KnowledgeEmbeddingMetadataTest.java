package com.example.codereview.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.ai.AiCallLogService;
import com.example.codereview.project.ProjectService;
import com.example.codereview.rag.EmbeddingClient;
import com.example.codereview.rag.EmbeddingJson;
import com.example.codereview.rag.RagService;
import com.example.codereview.rag.VectorIndexService;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class KnowledgeEmbeddingMetadataTest {

    @Test
    void persistsEmbeddingMetadataWithEveryIndexedChunk() {
        ProjectService projects = mock(ProjectService.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        RagService rag = mock(RagService.class);
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        EmbeddingJson json = mock(EmbeddingJson.class);
        VectorIndexService vectors = mock(VectorIndexService.class);
        AiCallLogService calls = mock(AiCallLogService.class);
        when(documents.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chunks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(embeddings.embed(any())).thenReturn(new EmbeddingClient.EmbeddingResult(
                "openai-compatible",
                "embedding-model",
                "embedding-model-v1",
                3,
                List.of(0.1, 0.2, 0.3)
        ));
        when(json.write(List.of(0.1, 0.2, 0.3))).thenReturn("[0.1,0.2,0.3]");
        KnowledgeService service = new KnowledgeService(
                projects, documents, chunks, rag, embeddings, json, vectors, calls,
                transactionManager(), 400, 20, false
        );

        service.upload(7L, 9L, "SECURITY", new MockMultipartFile(
                "file", "policy.md", "text/markdown", "# Policy\n\nReview policy".getBytes()
        ));

        ArgumentCaptor<KnowledgeChunk> captured = ArgumentCaptor.forClass(KnowledgeChunk.class);
        verify(chunks).save(captured.capture());
        KnowledgeChunk chunk = captured.getValue();
        assertThat(chunk.getEmbeddingProvider()).isEqualTo("openai-compatible");
        assertThat(chunk.getEmbeddingModel()).isEqualTo("embedding-model");
        assertThat(chunk.getEmbeddingVersion()).isEqualTo("embedding-model-v1");
        assertThat(chunk.getEmbeddingDimension()).isEqualTo(3);
        verify(vectors).index(chunk);
    }

    @Test
    void reindexIsProjectScopedIdempotentAndResumableByDocument() {
        ProjectService projects = mock(ProjectService.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        RagService rag = mock(RagService.class);
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        EmbeddingJson json = mock(EmbeddingJson.class);
        VectorIndexService vectors = mock(VectorIndexService.class);
        AiCallLogService calls = mock(AiCallLogService.class);
        KnowledgeDocument document = new KnowledgeDocument(
                7L, 9L, "SECURITY", "policy.md", "# Policy\n\nReview policy"
        );
        ReflectionTestUtils.setField(document, "id", 10L);
        KnowledgeChunk stale = new KnowledgeChunk(
                10L, 7L, "SECURITY", "policy.md", 0, "old", "[0.1,0.2]",
                "mock", "mock-hash-64", "mock-hash-v0", 2
        );
        AtomicReference<List<KnowledgeChunk>> stored = new AtomicReference<>(List.of(stale));
        when(documents.findByProjectIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(document));
        when(chunks.findByProjectIdAndDocumentIdIn(7L, List.of(10L)))
                .thenAnswer(invocation -> stored.get());
        when(embeddings.descriptor()).thenReturn(new EmbeddingClient.EmbeddingDescriptor(
                "mock", "mock-hash-64", "mock-hash-v1", 64
        ));
        when(embeddings.embed(any())).thenReturn(mockEmbedding());
        when(json.write(any())).thenReturn("[0.0]");
        when(chunks.save(any())).thenAnswer(invocation -> {
            KnowledgeChunk saved = invocation.getArgument(0);
            stored.set(List.of(saved));
            return saved;
        });
        when(documents.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            stored.set(List.of());
            return null;
        }).when(chunks).deleteByDocumentId(10L);
        KnowledgeService service = new KnowledgeService(
                projects, documents, chunks, rag, embeddings, json, vectors, calls,
                transactionManager(), 400, 20, false
        );

        KnowledgeDtos.ReindexResponse first = service.reindex(7L, 9L);
        KnowledgeDtos.ReindexResponse second = service.reindex(7L, 9L);

        assertThat(first.indexedDocuments()).isEqualTo(1);
        assertThat(first.failedDocuments()).isZero();
        assertThat(second.skippedDocuments()).isEqualTo(1);
        verify(embeddings, times(1)).embed(any());
        verify(vectors, times(1)).deleteByDocumentId(10L);
        verify(documents, times(2)).findByProjectIdOrderByCreatedAtDesc(7L);
    }

    @Test
    void deletionRemovesPgvectorRowsBeforeChunkMetadata() {
        ProjectService projects = mock(ProjectService.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        VectorIndexService vectors = mock(VectorIndexService.class);
        KnowledgeDocument document = new KnowledgeDocument(7L, 9L, "SECURITY", "policy.md", "text");
        ReflectionTestUtils.setField(document, "id", 10L);
        when(documents.findById(10L)).thenReturn(java.util.Optional.of(document));
        KnowledgeService service = new KnowledgeService(
                projects, documents, chunks, mock(RagService.class), mock(EmbeddingClient.class),
                mock(EmbeddingJson.class), vectors, mock(AiCallLogService.class),
                transactionManager(), 400, 20, false
        );

        service.delete(7L, 9L, 10L);

        verify(vectors).deleteByDocumentId(10L);
        verify(chunks).deleteByDocumentId(10L);
        verify(documents).delete(document);
    }

    @Test
    void reindexCommitsSuccessfulDocumentsAndRetriesOnlyFailedDocuments() {
        ProjectService projects = mock(ProjectService.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        EmbeddingJson json = mock(EmbeddingJson.class);
        VectorIndexService vectors = mock(VectorIndexService.class);
        KnowledgeDocument good = new KnowledgeDocument(7L, 9L, "POLICY", "good.md", "good policy");
        KnowledgeDocument retry = new KnowledgeDocument(7L, 9L, "POLICY", "retry.md", "retry policy");
        ReflectionTestUtils.setField(good, "id", 10L);
        ReflectionTestUtils.setField(retry, "id", 11L);
        AtomicReference<List<KnowledgeChunk>> goodChunks = new AtomicReference<>(List.of(
                staleChunk(10L, "good.md")
        ));
        AtomicReference<List<KnowledgeChunk>> retryChunks = new AtomicReference<>(List.of(
                staleChunk(11L, "retry.md")
        ));
        when(documents.findByProjectIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(good, retry));
        when(chunks.findByProjectIdAndDocumentIdIn(7L, List.of(10L)))
                .thenAnswer(invocation -> goodChunks.get());
        when(chunks.findByProjectIdAndDocumentIdIn(7L, List.of(11L)))
                .thenAnswer(invocation -> retryChunks.get());
        when(embeddings.descriptor()).thenReturn(new EmbeddingClient.EmbeddingDescriptor(
                "mock", "mock-hash-64", "mock-hash-v1", 64
        ));
        AtomicInteger retryAttempts = new AtomicInteger();
        when(embeddings.embed(any())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            if (text.contains("retry") && retryAttempts.getAndIncrement() == 0) {
                throw new IllegalStateException("temporary fixture failure");
            }
            return mockEmbedding();
        });
        when(json.write(any())).thenReturn("[0.0]");
        when(chunks.save(any())).thenAnswer(invocation -> {
            KnowledgeChunk saved = invocation.getArgument(0);
            if (saved.getDocumentId().equals(10L)) {
                goodChunks.set(List.of(saved));
            } else {
                retryChunks.set(List.of(saved));
            }
            return saved;
        });
        when(documents.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            goodChunks.set(List.of());
            return null;
        }).when(chunks).deleteByDocumentId(10L);
        doAnswer(invocation -> {
            retryChunks.set(List.of());
            return null;
        }).when(chunks).deleteByDocumentId(11L);
        KnowledgeService service = new KnowledgeService(
                projects, documents, chunks, mock(RagService.class), embeddings, json, vectors,
                mock(AiCallLogService.class), transactionManager(), 400, 20, false
        );

        KnowledgeDtos.ReindexResponse first = service.reindex(7L, 9L);
        KnowledgeDtos.ReindexResponse second = service.reindex(7L, 9L);

        assertThat(first.indexedDocuments()).isEqualTo(1);
        assertThat(first.failedDocuments()).isEqualTo(1);
        assertThat(second.indexedDocuments()).isEqualTo(1);
        assertThat(second.skippedDocuments()).isEqualTo(1);
        assertThat(second.failedDocuments()).isZero();
        assertThat(retryAttempts).hasValue(2);
    }

    private EmbeddingClient.EmbeddingResult mockEmbedding() {
        return new EmbeddingClient.EmbeddingResult(
                "mock", "mock-hash-64", "mock-hash-v1", 64,
                java.util.Collections.nCopies(64, 0.0)
        );
    }

    private KnowledgeChunk staleChunk(Long documentId, String sourceName) {
        return new KnowledgeChunk(
                documentId, 7L, "POLICY", sourceName, 0, "stale", "[0.0]",
                "mock", "mock-hash-64", "mock-hash-v0", 1
        );
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return manager;
    }
}
