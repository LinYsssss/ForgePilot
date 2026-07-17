package com.example.codereview.finding;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingDecisionRepository extends JpaRepository<FindingDecisionEntity, Long> {
    List<FindingDecisionEntity> findByFindingIdOrderByIdAsc(Long findingId);
}
