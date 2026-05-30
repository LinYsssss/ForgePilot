package com.example.codereview.knowledge;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    List<KnowledgeChunk> findByProjectId(Long projectId);

    List<KnowledgeChunk> findByProjectIdAndDocumentIdIn(Long projectId, Collection<Long> documentIds);

    void deleteByDocumentId(Long documentId);

    void deleteByProjectId(Long projectId);
}
