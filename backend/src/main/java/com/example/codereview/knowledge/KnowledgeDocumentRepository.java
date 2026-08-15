package com.example.codereview.knowledge;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    List<KnowledgeDocument> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /** 给 API 用的分页版本;无界的那个留给 reindex 等内部调用方。 */
    org.springframework.data.domain.Page<KnowledgeDocument> findByProjectIdOrderByCreatedAtDesc(
            Long projectId, org.springframework.data.domain.Pageable pageable);

    void deleteByProjectId(Long projectId);
}
