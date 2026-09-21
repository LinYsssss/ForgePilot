package com.forgepilot.scm;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** 绑定行的读取；按状态的单行查询依赖 V8 的 ACTIVE / PENDING 两个部分唯一索引。 */
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
