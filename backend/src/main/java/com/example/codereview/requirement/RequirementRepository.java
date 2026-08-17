package com.example.codereview.requirement;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RequirementRepository extends JpaRepository<RequirementEntity, Long> {

    Page<RequirementEntity> findByProjectIdOrderBySeqDesc(Long projectId, Pageable pageable);

    Page<RequirementEntity> findByProjectIdAndStatusOrderBySeqDesc(Long projectId, String status, Pageable pageable);

    Optional<RequirementEntity> findByIdAndProjectId(Long id, Long projectId);

    Optional<RequirementEntity> findByProjectIdAndSeq(Long projectId, Long seq);

    @Query("select coalesce(max(r.seq), 0) from RequirementEntity r where r.projectId = :projectId")
    long maxSeq(Long projectId);

    void deleteByProjectId(Long projectId);

    List<RequirementEntity> findByProjectIdOrderBySeqDesc(Long projectId);
}
