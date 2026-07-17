package com.example.codereview.finding;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingEvidenceRepository extends JpaRepository<FindingEvidenceEntity, Long> {
    List<FindingEvidenceEntity> findByFindingIdOrderByIdAsc(Long findingId);
}
