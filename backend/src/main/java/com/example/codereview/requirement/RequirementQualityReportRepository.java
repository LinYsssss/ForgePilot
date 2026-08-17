package com.example.codereview.requirement;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RequirementQualityReportRepository extends JpaRepository<RequirementQualityReportEntity, Long> {

    List<RequirementQualityReportEntity> findByRequirementIdOrderByRoundDesc(Long requirementId);

    @Query("select coalesce(max(r.round), 0) from RequirementQualityReportEntity r where r.requirementId = :requirementId")
    int maxRound(Long requirementId);

    void deleteByRequirementIdIn(List<Long> requirementIds);
}
