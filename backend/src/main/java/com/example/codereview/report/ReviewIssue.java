package com.example.codereview.report;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "review_issue")
public class ReviewIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reportId;

    @Column(nullable = false, length = 32)
    private String severity;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(length = 512)
    private String filePath;

    private Integer lineStart;
    private Integer lineEnd;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String impact;

    @Column(columnDefinition = "text")
    private String evidence;

    @Column(columnDefinition = "text")
    private String suggestion;

    private Double confidence;

    @Column(nullable = false)
    private Instant createdAt;

    protected ReviewIssue() {
    }

    public ReviewIssue(Long reportId, String severity, String category, String filePath, Integer lineStart, Integer lineEnd,
                       String title, String description, String impact, String evidence, String suggestion, Double confidence) {
        this.reportId = reportId;
        this.severity = severity;
        this.category = category;
        this.filePath = filePath;
        this.lineStart = lineStart;
        this.lineEnd = lineEnd;
        this.title = title;
        this.description = description;
        this.impact = impact;
        this.evidence = evidence;
        this.suggestion = suggestion;
        this.confidence = confidence;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getSeverity() {
        return severity;
    }

    public String getCategory() {
        return category;
    }

    public String getFilePath() {
        return filePath;
    }

    public Integer getLineStart() {
        return lineStart;
    }

    public Integer getLineEnd() {
        return lineEnd;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getImpact() {
        return impact;
    }

    public String getEvidence() {
        return evidence;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public Double getConfidence() {
        return confidence;
    }
}
