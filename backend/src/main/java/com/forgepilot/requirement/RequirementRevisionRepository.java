package com.forgepilot.requirement;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Every read carries {@code projectId}; revisions are never looked up by bare id. */
public interface RequirementRevisionRepository extends JpaRepository<RequirementRevision, Long> {

    List<RequirementRevision> findByProjectIdAndRequirementIdOrderBySeqAsc(long projectId, long requirementId);
}
