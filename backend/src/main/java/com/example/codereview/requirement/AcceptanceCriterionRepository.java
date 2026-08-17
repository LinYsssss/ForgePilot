package com.example.codereview.requirement;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AcceptanceCriterionRepository extends JpaRepository<AcceptanceCriterionEntity, Long> {

    List<AcceptanceCriterionEntity> findByRequirementIdOrderBySeqAsc(Long requirementId);

    List<AcceptanceCriterionEntity> findByRequirementIdInOrderBySeqAsc(List<Long> requirementIds);

    void deleteByRequirementId(Long requirementId);

    void deleteByRequirementIdIn(List<Long> requirementIds);
}
