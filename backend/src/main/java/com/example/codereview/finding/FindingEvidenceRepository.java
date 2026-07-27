package com.example.codereview.finding;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingEvidenceRepository extends JpaRepository<FindingEvidenceEntity, Long> {
    List<FindingEvidenceEntity> findByFindingIdOrderByIdAsc(Long findingId);

    /** 批量取,避免按 Finding 逐条查询造成 N+1。 */
    List<FindingEvidenceEntity> findByFindingIdInOrderByIdAsc(Collection<Long> findingIds);
}
