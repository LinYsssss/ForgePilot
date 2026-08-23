package com.forgepilot.knowledge;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** 每次读取都带 {@code projectId}；这里根本不存在“日后再补一道检查”的裸 id 查询。 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    Optional<KnowledgeDocument> findByProjectIdAndId(long projectId, long id);
}
