package com.forgepilot.knowledge;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Every read carries {@code projectId}; there is no bare-id lookup to bolt a check onto later. */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    Optional<KnowledgeDocument> findByProjectIdAndId(long projectId, long id);
}
