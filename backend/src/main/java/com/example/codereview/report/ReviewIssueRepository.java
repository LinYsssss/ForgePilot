package com.example.codereview.report;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewIssueRepository extends JpaRepository<ReviewIssue, Long> {

    List<ReviewIssue> findByReportId(Long reportId);
}
