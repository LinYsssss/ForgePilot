package com.example.codereview.finding;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingScoreContributionRepository extends JpaRepository<FindingScoreContributionEntity, Long> {
    List<FindingScoreContributionEntity> findByDecisionIdOrderByIdAsc(Long decisionId);
}
