package com.example.codereview.rag;

import com.example.codereview.knowledge.KnowledgeChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.rag", name = "mode", havingValue = "memory", matchIfMissing = true)
public class NoopVectorIndexService implements VectorIndexService {

    @Override
    public void index(KnowledgeChunk chunk) {
        // Memory mode stores embedding JSON directly in knowledge_chunk.
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        // Memory mode stores vectors on knowledge_chunk, so chunk deletion is enough.
    }
}
