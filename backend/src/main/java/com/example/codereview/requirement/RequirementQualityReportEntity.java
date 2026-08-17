package com.example.codereview.requirement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "requirement_quality_report",
        uniqueConstraints = @UniqueConstraint(name = "uq_requirement_quality_round",
                columnNames = {"requirementId", "round"}))
public class RequirementQualityReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long requirementId;

    @Column(nullable = false)
    private Integer round;

    @Column(nullable = false, columnDefinition = "text")
    private String reportJson;

    @Column(nullable = false, length = 160)
    private String model;

    @Column(nullable = false)
    private int totalTokens;

    @Column(nullable = false)
    private Instant createdAt;

    protected RequirementQualityReportEntity() {
    }

    public RequirementQualityReportEntity(Long requirementId, Integer round, String reportJson,
                                          String model, int totalTokens) {
        this.requirementId = requirementId;
        this.round = round;
        this.reportJson = reportJson;
        this.model = model;
        this.totalTokens = totalTokens;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getRequirementId() {
        return requirementId;
    }

    public Integer getRound() {
        return round;
    }

    public String getReportJson() {
        return reportJson;
    }

    public String getModel() {
        return model;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
