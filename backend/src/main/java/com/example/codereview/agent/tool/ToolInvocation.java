package com.example.codereview.agent.tool;

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
        name = "tool_invocation",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_tool_invocation_key",
                columnNames = "invocationKey"
        ),
        indexes = @Index(name = "idx_tool_invocation_run", columnList = "agentRunId,agentStepId")
)
public class ToolInvocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160, updatable = false)
    private String invocationKey;

    @Column(nullable = false)
    private Long agentRunId;

    @Column(nullable = false)
    private Long agentStepId;

    @Column(nullable = false, length = 120)
    private String toolName;

    @Column(nullable = false, columnDefinition = "text")
    private String inputJson;

    @Column(columnDefinition = "text")
    private String outputJson;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(nullable = false)
    private long durationMs;

    @Column(nullable = false)
    private int toolCalls;

    @Column(nullable = false)
    private long inputBytes;

    @Column(nullable = false)
    private long outputBytes;

    @Column(columnDefinition = "text")
    private String errorMessage;

    @Column(length = 128)
    private String correlationId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ToolInvocation() {
    }

    private ToolInvocation(
            String invocationKey,
            Long agentRunId,
            Long agentStepId,
            String toolName,
            String inputJson,
            String correlationId
    ) {
        this.invocationKey = invocationKey;
        this.agentRunId = agentRunId;
        this.agentStepId = agentStepId;
        this.toolName = toolName;
        this.inputJson = inputJson;
        this.inputBytes = inputJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        this.status = "RUNNING";
        this.toolCalls = 1;
        this.correlationId = correlationId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static ToolInvocation started(
            String invocationKey,
            Long agentRunId,
            Long agentStepId,
            String toolName,
            String inputJson,
            String correlationId
    ) {
        return new ToolInvocation(
                invocationKey, agentRunId, agentStepId, toolName, inputJson, correlationId
        );
    }

    public void succeed(String outputJson, long durationMs) {
        this.outputJson = outputJson;
        this.outputBytes = outputJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        this.durationMs = durationMs;
        this.status = "SUCCEEDED";
        this.updatedAt = Instant.now();
    }

    public void fail(String errorMessage, long durationMs) {
        this.errorMessage = errorMessage;
        this.durationMs = durationMs;
        this.status = "FAILED";
        this.updatedAt = Instant.now();
    }

    public boolean isSucceeded() {
        return "SUCCEEDED".equals(status);
    }

    public String getOutputJson() {
        return outputJson;
    }
}
