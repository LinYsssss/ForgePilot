package com.example.codereview.finding;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingDecisionRepository extends JpaRepository<FindingDecisionEntity, Long> {
    List<FindingDecisionEntity> findByFindingIdOrderByIdAsc(Long findingId);

    /** 批量取,避免按 Finding 逐条查询造成 N+1。 */
    List<FindingDecisionEntity> findByFindingIdInOrderByIdAsc(Collection<Long> findingIds);
}
