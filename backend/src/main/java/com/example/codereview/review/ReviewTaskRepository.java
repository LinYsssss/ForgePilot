package com.example.codereview.review;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewTaskRepository extends JpaRepository<ReviewTask, Long> {

    List<ReviewTask> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    Optional<ReviewTask> findFirstByProjectIdAndRepositoryIdAndCommitIdAndBaseCommitIdAndBranchNameOrderByCreatedAtDesc(
            Long projectId,
            Long repositoryId,
            String commitId,
            String baseCommitId,
            String branchName
    );
}
