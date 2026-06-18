package com.example.codereview.pullrequest;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PullRequestRepository extends JpaRepository<PullRequestEntity, Long> {

    List<PullRequestEntity> findByProjectIdOrderByUpdatedAtDesc(Long projectId);

    void deleteByProjectId(Long projectId);
}
