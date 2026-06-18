package com.example.codereview.review;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewTaskRepository extends JpaRepository<ReviewTask, Long> {

    List<ReviewTask> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    void deleteByProjectId(Long projectId);

    Optional<ReviewTask> findFirstByProjectIdAndRepositoryIdAndCommitIdAndBaseCommitIdAndBranchNameOrderByCreatedAtDesc(
            Long projectId,
            Long repositoryId,
            String commitId,
            String baseCommitId,
            String branchName
    );

    @Query("""
            select t from ReviewTask t
            where t.projectId = :projectId
              and t.repositoryId = :repositoryId
              and t.commitId = :commitId
              and t.baseCommitIdNormalized = :baseCommitIdNormalized
              and t.branchName = :branchName
            order by t.createdAt desc
            """)
    Optional<ReviewTask> findIdempotentTask(
            @Param("projectId") Long projectId,
            @Param("repositoryId") Long repositoryId,
            @Param("commitId") String commitId,
            @Param("baseCommitIdNormalized") String baseCommitIdNormalized,
            @Param("branchName") String branchName
    );
}
