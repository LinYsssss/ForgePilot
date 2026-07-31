package com.example.codereview.knowledge;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    List<KnowledgeDocument> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /** Paginated variant for the API; the unbounded one stays for internal callers such as reindex. */
    org.springframework.data.domain.Page<KnowledgeDocument> findByProjectIdOrderByCreatedAtDesc(
            Long projectId, org.springframework.data.domain.Pageable pageable);

    void deleteByProjectId(Long projectId);
}
