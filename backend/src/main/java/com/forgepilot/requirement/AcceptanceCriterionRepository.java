package com.forgepilot.requirement;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Every read carries {@code projectId}; criteria are never looked up by bare id. */
public interface AcceptanceCriterionRepository extends JpaRepository<AcceptanceCriterion, Long> {

    List<AcceptanceCriterion> findByProjectIdAndRequirementRevisionIdOrderBySortOrderAsc(
            long projectId, long requirementRevisionId);

    List<AcceptanceCriterion> findByProjectIdAndRequirementRevisionIdInOrderBySortOrderAsc(
            long projectId, Collection<Long> requirementRevisionIds);

    /**
     * Every {@code acKey} this requirement has ever used, across all of its
     * revisions. Retired numbers are never reused, so the next key is derived from
     * this set and not from the current revision alone (api-contract 3).
     */
    @Query("select criterion.acKey from AcceptanceCriterion criterion, RequirementRevision revision "
            + "where criterion.projectId = :projectId "
            + "and revision.id = criterion.requirementRevisionId "
            + "and revision.requirementId = :requirementId")
    List<String> findKeysOfRequirement(long projectId, long requirementId);
}
