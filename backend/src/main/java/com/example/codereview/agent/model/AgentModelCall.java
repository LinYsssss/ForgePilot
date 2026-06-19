package com.example.codereview.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(
        name = "agent_model_call",
        indexes = @Index(name = "idx_agent_model_call_run", columnList = "agentRunId,createdAt")
)
public class AgentModelCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long agentRunId;

    @Column(nullable = false, length = 80)
    private String provider;

    @Column(nullable = false, length = 160)
    private String model;

    @Column(nullable = false, length = 40)
    private String promptVersion;

    @Column(nullable = false, length = 40)
    private String schemaVersion;

    @Column(nullable = false)
    private long inputTokens;

    @Column(nullable = false)
    private long outputTokens;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(columnDefinition = "text")
    private String failureReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentModelCall() {
    }

    public AgentModelCall(
            Long agentRunId,
            AgentModelClient.ModelResponse response,
            PromptEnvelope prompt
    ) {
        this.agentRunId = agentRunId;
        this.provider = response.provider();
        this.model = response.model();
        this.promptVersion = prompt.promptVersion();
        this.schemaVersion = prompt.schemaVersion();
        this.inputTokens = response.inputTokens();
        this.outputTokens = response.outputTokens();
        this.status = "RECEIVED";
        this.createdAt = Instant.now();
    }

    public void complete() {
        this.status = "VALID";
        this.failureReason = null;
    }

    public void fail(String reason) {
        this.status = "INVALID";
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 2_000));
    }
}
