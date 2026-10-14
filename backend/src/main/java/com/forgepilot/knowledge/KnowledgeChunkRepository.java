package com.forgepilot.knowledge;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    List<KnowledgeChunk> findByProjectIdAndDocumentIdOrderBySeqAsc(long projectId, long documentId);

    /** 删除留痕只需要个数；把每个 chunk 连正文一起加载进内存只为数一下，在大文档上是纯浪费。 */
    long countByProjectIdAndDocumentId(long projectId, long documentId);

    /** Chunk 与它的向量都是派生数据，随文档一起消亡。 */
    void deleteByProjectIdAndDocumentId(long projectId, long documentId);
}
