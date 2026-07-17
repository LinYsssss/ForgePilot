package com.example.codereview.agent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
        name = "review_plan",
        uniqueConstraints = @UniqueConstraint(name = "uq_review_plan_run", columnNames = "agentRunId"),
        indexes = @Index(name = "idx_review_plan_run", columnList = "agentRunId")
)
public class ReviewPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long agentRunId;

    @Column(nullable = false, length = 40)
    private String schemaVersion;

    @Column(nullable = false, columnDefinition = "text")
    private String modelResponseJson;

    @Column(columnDefinition = "text")
    private String validatedPlanJson;

    @Column(columnDefinition = "text")
    private String validationErrors;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ReviewPlan() {
    }

    public ReviewPlan(Long agentRunId, String schemaVersion, String modelResponseJson) {
        this.agentRunId = agentRunId;
        this.schemaVersion = schemaVersion;
        this.modelResponseJson = modelResponseJson;
        this.status = "PENDING_VALIDATION";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void accept(String validatedPlanJson) {
        this.validatedPlanJson = validatedPlanJson;
        this.validationErrors = null;
        this.status = "VALID";
        this.updatedAt = Instant.now();
    }

    public void reject(String validationErrors) {
        this.validationErrors = validationErrors;
        this.validatedPlanJson = null;
        this.status = "INVALID";
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public String getValidatedPlanJson() {
        return validatedPlanJson;
    }

    public String getModelResponseJson() {
        return modelResponseJson;
    }

    public record PlanItem(
            String toolName,
            JsonNode arguments,
            String purpose,
            String expectedEvidence,
            String modelRequestId
    ) {
        public PlanItem(String toolName, JsonNode arguments, String purpose, String expectedEvidence) {
            this(toolName, arguments, purpose, expectedEvidence, null);
        }
    }
}
