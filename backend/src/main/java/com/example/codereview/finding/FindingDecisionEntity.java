package com.example.codereview.finding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "finding_decision")
public class FindingDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "finding_id", nullable = false)
    private Long findingId;

    @Column(name = "weight_version", nullable = false, length = 80)
    private String weightVersion;

    @Column(nullable = false)
    private Double threshold;

    @Column(nullable = false)
    private Double confidence;

    @Column(nullable = false)
    private Boolean blocking;

    @Column(nullable = false, length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FindingDecisionEntity() {
    }

    public static FindingDecisionEntity from(Long findingId, GateDecision decision) {
        if (findingId == null || findingId <= 0 || decision == null) {
            throw new IllegalArgumentException("findingId and decision are required");
        }
        FindingDecisionEntity entity = new FindingDecisionEntity();
        entity.findingId = findingId;
        entity.weightVersion = decision.weightVersion();
        entity.threshold = decision.threshold();
        entity.confidence = decision.confidence();
        entity.blocking = decision.blocking();
        entity.reason = decision.reason();
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

    public Long getFindingId() {
        return findingId;
    }

    public String getWeightVersion() {
        return weightVersion;
    }

    public Double getThreshold() {
        return threshold;
    }

    public Double getConfidence() {
        return confidence;
    }

    public Boolean getBlocking() {
        return blocking;
    }

    public String getReason() {
        return reason;
    }
}
