package com.example.codereview.member;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectMemberRepository extends JpaRepository<ProjectMemberEntity, Long> {

    Optional<ProjectMemberEntity> findByProjectIdAndUserId(Long projectId, Long userId);

    boolean existsByProjectIdAndUserId(Long projectId, Long userId);

    List<ProjectMemberEntity> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    List<ProjectMemberEntity> findByUserId(Long userId);

    void deleteByProjectId(Long projectId);
}
