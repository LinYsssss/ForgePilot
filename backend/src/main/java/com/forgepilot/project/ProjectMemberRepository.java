package com.forgepilot.project;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 每次读取都带 {@code projectId}；这里根本不存在“日后再补一道检查”的裸 id 查询。 */
public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {

    Optional<ProjectMember> findByProjectIdAndUserId(long projectId, long userId);

    List<ProjectMember> findByProjectIdOrderByIdAsc(long projectId);

    List<ProjectMember> findByUserIdOrderByProjectIdAsc(long userId);

    @Query("select distinct m from ProjectMember m join m.roles r where m.projectId = :projectId and r = :role")
    Optional<ProjectMember> findByProjectIdAndRole(long projectId, ProjectRole role);
}
