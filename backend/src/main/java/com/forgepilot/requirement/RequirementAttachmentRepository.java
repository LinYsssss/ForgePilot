package com.forgepilot.requirement;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RequirementAttachmentRepository extends JpaRepository<RequirementAttachment, Long> {

    List<RequirementAttachment> findByProjectIdAndRequirementIdOrderByIdAsc(long projectId, long requirementId);
}
