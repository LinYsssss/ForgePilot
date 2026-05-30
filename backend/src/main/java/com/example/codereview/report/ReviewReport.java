package com.example.codereview.report;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "review_report")
public class ReviewReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 80)
    private String commitId;

    @Column(nullable = false, length = 32)
    private String overallRisk;

    @Column(nullable = false)
    private int issueCount;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(columnDefinition = "text")
    private String rawAiResponse;

    @Column(nullable = false)
    private Instant createdAt;

    protected ReviewReport() {
    }

    public ReviewReport(Long taskId, Long projectId, String commitId, String overallRisk, int issueCount, String summary, String rawAiResponse) {
        this.taskId = taskId;
        this.projectId = projectId;
        this.commitId = commitId;
        this.overallRisk = overallRisk;
        this.issueCount = issueCount;
        this.summary = summary;
        this.rawAiResponse = rawAiResponse;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getCommitId() {
        return commitId;
    }

    public String getOverallRisk() {
        return overallRisk;
    }

    public int getIssueCount() {
        return issueCount;
    }

    public String getSummary() {
        return summary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
