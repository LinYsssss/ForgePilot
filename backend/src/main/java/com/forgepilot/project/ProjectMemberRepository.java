package com.forgepilot.project;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Every read carries {@code projectId}; there is no bare-id lookup to bolt a check onto later. */
public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {

    Optional<ProjectMember> findByProjectIdAndUserId(long projectId, long userId);

    List<ProjectMember> findByProjectIdOrderByIdAsc(long projectId);

    List<ProjectMember> findByUserIdOrderByProjectIdAsc(long userId);

    Optional<ProjectMember> findByProjectIdAndRole(long projectId, ProjectRole role);
}
