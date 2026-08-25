package com.forgepilot.scm;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectMemberScmBindingRepository extends JpaRepository<ProjectMemberScmBinding, Long> {
    List<ProjectMemberScmBinding> findByProjectIdOrderByIdAsc(long projectId);
    List<ProjectMemberScmBinding> findByProjectIdAndUserIdOrderByIdAsc(long projectId, long userId);
    List<ProjectMemberScmBinding> findByProjectIdAndStatus(
            long projectId, ProjectMemberScmBinding.Status status);
    Optional<ProjectMemberScmBinding> findByProjectIdAndId(long projectId, long id);
    Optional<ProjectMemberScmBinding> findByProjectIdAndUserIdAndStatus(
            long projectId, long userId, ProjectMemberScmBinding.Status status);
    List<ProjectMemberScmBinding> findByScmIdentityIdAndStatusIn(
            long identityId, List<ProjectMemberScmBinding.Status> statuses);
}
