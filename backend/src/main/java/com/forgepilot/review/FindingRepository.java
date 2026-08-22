package com.forgepilot.review;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingRepository extends JpaRepository<Finding, Long> {

    Optional<Finding> findByProjectIdAndId(long projectId, long id);

    List<Finding> findByProjectIdAndReviewIdOrderByIdAsc(long projectId, long reviewId);
}
