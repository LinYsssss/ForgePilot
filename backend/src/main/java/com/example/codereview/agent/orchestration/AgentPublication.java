package com.example.codereview.agent.orchestration;

import com.example.codereview.scm.ScmPublicationResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "agent_publication", uniqueConstraints = @UniqueConstraint(
        name = "uq_agent_publication_key", columnNames = "idempotencyKey"
))
public class AgentPublication {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, updatable = false) private Long agentRunId;
    @Column(nullable = false, length = 200, updatable = false) private String idempotencyKey;
    @Column(nullable = false, length = 32) private String status;
    @Column(length = 255) private String responseCodes;
    @Column(length = 2000) private String message;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected AgentPublication() {}

    public AgentPublication(Long agentRunId, String idempotencyKey) {
        this.agentRunId = agentRunId;
        this.idempotencyKey = idempotencyKey;
        this.status = "PENDING";
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void record(ScmPublicationResult result) {
        this.status = result.success() ? "PUBLISHED" : "FAILED";
        this.responseCodes = result.responseCodes().toString();
        this.message = result.message();
        this.updatedAt = Instant.now();
    }

    public boolean isPublished() { return "PUBLISHED".equals(status); }
    public Long getId() { return id; }
    public Long getAgentRunId() { return agentRunId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getStatus() { return status; }
    public String getResponseCodes() { return responseCodes; }
    public String getMessage() { return message; }
}
