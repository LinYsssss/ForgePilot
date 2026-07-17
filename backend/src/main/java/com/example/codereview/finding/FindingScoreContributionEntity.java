package com.example.codereview.finding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "finding_score_contribution")
public class FindingScoreContributionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "decision_id", nullable = false)
    private Long decisionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ConfidenceFactor factor;

    @Column(nullable = false)
    private Double weight;

    @Column(nullable = false)
    private Double signal;

    @Column(nullable = false)
    private Double contribution;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FindingScoreContributionEntity() {
    }

    public static FindingScoreContributionEntity from(Long decisionId, ScoreContribution contribution) {
        if (decisionId == null || decisionId <= 0 || contribution == null) {
            throw new IllegalArgumentException("decisionId and contribution are required");
        }
        FindingScoreContributionEntity entity = new FindingScoreContributionEntity();
        entity.decisionId = decisionId;
        entity.factor = contribution.factor();
        entity.weight = contribution.weight();
        entity.signal = contribution.signal();
        entity.contribution = contribution.contribution();
        return entity;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getDecisionId() {
        return decisionId;
    }

    public ConfidenceFactor getFactor() {
        return factor;
    }

    public Double getWeight() {
        return weight;
    }

    public Double getSignal() {
        return signal;
    }

    public Double getContribution() {
        return contribution;
    }
}
