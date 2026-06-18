package com.example.codereview.pullrequest;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewActionRepository extends JpaRepository<ReviewAction, Long> {

    List<ReviewAction> findByPullRequestIdOrderByCreatedAtDesc(Long pullRequestId);

    void deleteByProjectId(Long projectId);
}
