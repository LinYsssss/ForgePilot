package com.example.codereview.feedback;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    List<Feedback> findByIssueIdOrderByCreatedAtDesc(Long issueId);

    Optional<Feedback> findByIssueIdAndUserId(Long issueId, Long userId);

    long countByIssueId(Long issueId);

    void deleteByIssueIdIn(Collection<Long> issueIds);
}
