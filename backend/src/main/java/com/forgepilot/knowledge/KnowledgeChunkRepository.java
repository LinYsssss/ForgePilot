package com.forgepilot.knowledge;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    List<KnowledgeChunk> findByProjectIdAndDocumentIdOrderBySeqAsc(long projectId, long documentId);
}
