package com.example.codereview.requirement;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequirementLinkRepository extends JpaRepository<RequirementLinkEntity, Long> {

    List<RequirementLinkEntity> findByRequirementIdOrderByCreatedAtAsc(Long requirementId);

    Optional<RequirementLinkEntity> findByRequirementIdAndLinkTypeAndRef(Long requirementId, String linkType, String ref);

    List<RequirementLinkEntity> findByProjectIdAndLinkTypeAndRef(Long projectId, String linkType, String ref);

    void deleteByProjectId(Long projectId);
}
