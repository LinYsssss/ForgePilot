package com.example.codereview.rag;

import com.example.codereview.knowledge.KnowledgeChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.rag", name = "mode", havingValue = "memory", matchIfMissing = true)
public class NoopVectorIndexService implements VectorIndexService {

    @Override
    public void index(KnowledgeChunk chunk) {
        // 内存模式把 embedding JSON 直接存在 knowledge_chunk 上。
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        // 内存模式的向量就挂在 knowledge_chunk 上,删掉分块即可,无需额外清理。
    }
}
