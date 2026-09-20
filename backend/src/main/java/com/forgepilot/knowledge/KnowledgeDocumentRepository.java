package com.forgepilot.knowledge;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** 每次读取都带 {@code projectId}；这里根本不存在“日后再补一道检查”的裸 id 查询。 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    Optional<KnowledgeDocument> findByProjectIdAndId(long projectId, long id);

    /** Only the serial background processor scans across projects; subsequent writes carry both IDs. */
    Optional<KnowledgeDocument> findFirstByStatusOrderByIdAsc(KnowledgeStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from KnowledgeDocument d where d.projectId = :projectId and d.id = :id")
    Optional<KnowledgeDocument> lockByProjectIdAndId(long projectId, long id);
}
