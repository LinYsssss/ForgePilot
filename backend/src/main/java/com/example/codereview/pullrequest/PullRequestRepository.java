package com.example.codereview.pullrequest;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PullRequestRepository extends JpaRepository<PullRequestEntity, Long> {

    List<PullRequestEntity> findByProjectIdOrderByUpdatedAtDesc(Long projectId);

    Optional<PullRequestEntity> findByProjectIdAndPrNumber(Long projectId, Integer prNumber);

    Optional<PullRequestEntity> findByProjectIdAndExternalPrId(Long projectId, String externalPrId);

    void deleteByProjectId(Long projectId);
}
