package com.example.codereview.rag;

import com.example.codereview.knowledge.KnowledgeChunk;

public interface VectorIndexService {

    void index(KnowledgeChunk chunk);

    void deleteByDocumentId(Long documentId);
}
