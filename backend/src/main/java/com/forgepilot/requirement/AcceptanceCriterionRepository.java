package com.forgepilot.requirement;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 每次读取都带 {@code projectId}；验收条件绝不按裸 id 查询。 */
public interface AcceptanceCriterionRepository extends JpaRepository<AcceptanceCriterion, Long> {

    List<AcceptanceCriterion> findByProjectIdAndRequirementRevisionIdOrderBySortOrderAsc(
            long projectId, long requirementRevisionId);

    List<AcceptanceCriterion> findByProjectIdAndRequirementRevisionIdInOrderBySortOrderAsc(
            long projectId, Collection<Long> requirementRevisionIds);

    /**
     * 这条需求在其**全部**修订中用过的每一个 {@code acKey}。退役的编号绝不复用，
     * 因此下一个 key 是从这个集合推出来的，而不是只看当前修订（api-contract 3）。
     */
    @Query("select criterion.acKey from AcceptanceCriterion criterion, RequirementRevision revision "
            + "where criterion.projectId = :projectId "
            + "and revision.id = criterion.requirementRevisionId "
            + "and revision.requirementId = :requirementId")
    List<String> findKeysOfRequirement(long projectId, long requirementId);
}
