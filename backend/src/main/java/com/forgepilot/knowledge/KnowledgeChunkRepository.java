package com.forgepilot.knowledge;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    List<KnowledgeChunk> findByProjectIdAndDocumentIdOrderBySeqAsc(long projectId, long documentId);

    /** Chunk 与它的向量都是派生数据，随文档一起消亡（D022）。 */
    void deleteByProjectIdAndDocumentId(long projectId, long documentId);
}
