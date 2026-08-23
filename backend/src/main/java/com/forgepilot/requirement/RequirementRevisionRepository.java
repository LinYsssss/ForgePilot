package com.forgepilot.requirement;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** 每次读取都带 {@code projectId}；修订绝不按裸 id 查询。 */
public interface RequirementRevisionRepository extends JpaRepository<RequirementRevision, Long> {

    List<RequirementRevision> findByProjectIdAndRequirementIdOrderBySeqAsc(long projectId, long requirementId);
}
