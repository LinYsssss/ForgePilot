package com.example.codereview.agent.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
        name = "agent_step",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_agent_step_sequence",
                columnNames = {"agentRunId", "sequenceNo"}
        ),
        indexes = @Index(name = "idx_agent_step_run", columnList = "agentRunId,sequenceNo")
)
public class AgentStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long agentRunId;

    @Column(nullable = false)
    private int sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AgentRunStatus stepType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AgentStepStatus status;

    @Column(nullable = false)
    private int attempt;

    @Column(columnDefinition = "text")
    private String inputSummary;

    @Column(columnDefinition = "text")
    private String outputSummary;

    @Column(columnDefinition = "text")
    private String errorMessage;

    private Instant startedAt;
    private Instant finishedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected AgentStep() {
    }

    private AgentStep(Long agentRunId, int sequenceNo, AgentRunStatus stepType) {
        this.agentRunId = agentRunId;
        this.sequenceNo = sequenceNo;
        this.stepType = stepType;
        this.status = AgentStepStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static AgentStep pending(Long agentRunId, int sequenceNo, AgentRunStatus stepType) {
        return new AgentStep(agentRunId, sequenceNo, stepType);
    }

    public boolean isSucceeded() {
        return status == AgentStepStatus.SUCCEEDED;
    }

    public boolean isRunningAttempt(int messageAttempt) {
        return status == AgentStepStatus.RUNNING && attempt == messageAttempt;
    }

    public boolean acceptsAttempt(int messageAttempt) {
        if (status == AgentStepStatus.SUCCEEDED || status == AgentStepStatus.CANCELED) {
            return false;
        }
        if (status == AgentStepStatus.RUNNING) {
            return false;
        }
        if (status == AgentStepStatus.FAILED || status == AgentStepStatus.INTERRUPTED) {
            return messageAttempt == attempt + 1;
        }
        return messageAttempt == attempt;
    }

    public void start(int messageAttempt) {
        if (!acceptsAttempt(messageAttempt)) {
            throw new IllegalStateException(
                    "Step does not accept attempt " + messageAttempt + " while " + status + "/" + attempt
            );
        }
        this.attempt = messageAttempt;
        this.status = AgentStepStatus.RUNNING;
        this.startedAt = Instant.now();
        this.finishedAt = null;
        this.errorMessage = null;
        this.updatedAt = this.startedAt;
    }

    public void succeed(String outputSummary) {
        this.status = AgentStepStatus.SUCCEEDED;
        this.outputSummary = truncate(outputSummary, 8_000);
        this.errorMessage = null;
        this.finishedAt = Instant.now();
        this.updatedAt = this.finishedAt;
    }

    public void fail(String errorMessage) {
        this.status = AgentStepStatus.FAILED;
        this.errorMessage = truncate(errorMessage, 2_000);
        this.finishedAt = Instant.now();
        this.updatedAt = this.finishedAt;
    }

    public void cancel() {
        this.status = AgentStepStatus.CANCELED;
        this.finishedAt = Instant.now();
        this.updatedAt = this.finishedAt;
    }

    public boolean isTerminal() {
        return status == AgentStepStatus.SUCCEEDED
                || status == AgentStepStatus.CANCELED
                || status == AgentStepStatus.SKIPPED;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.substring(0, Math.min(value.length(), maxLength));
    }
    public Long getId() {
        return id;
    }

    public Long getAgentRunId() {
        return agentRunId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public AgentRunStatus getStepType() {
        return stepType;
    }

    public AgentStepStatus getStatus() {
        return status;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getInputSummary() {
        return inputSummary;
    }

    public String getOutputSummary() {
        return outputSummary;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
