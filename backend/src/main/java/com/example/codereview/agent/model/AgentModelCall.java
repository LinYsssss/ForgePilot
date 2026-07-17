package com.example.codereview.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

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

    @Column(nullable = false, length = 64)
    private String promptHash;

    @Column(nullable = false)
    private long inputTokens;

    @Column(nullable = false)
    private long outputTokens;

    @Column(nullable = false, length = 24)
    private String callPurpose;

    @Column(nullable = false, length = 40)
    private String finishReason;

    @Column(nullable = false)
    private long latencyMs;

    @Column(nullable = false, length = 64)
    private String responseHash;

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
        this(agentRunId, response, prompt, "GENERATE");
    }

    public AgentModelCall(
            Long agentRunId,
            AgentModelClient.ModelResponse response,
            PromptEnvelope prompt,
            String callPurpose
    ) {
        this.agentRunId = agentRunId;
        this.provider = response.provider();
        this.model = response.model();
        this.promptVersion = prompt.promptVersion();
        this.schemaVersion = prompt.schemaVersion();
        this.promptHash = prompt.promptHash();
        this.inputTokens = response.inputTokens();
        this.outputTokens = response.outputTokens();
        this.callPurpose = callPurpose;
        this.finishReason = response.finishReason();
        this.latencyMs = response.latencyMs();
        this.responseHash = sha256(response.content());
        this.status = "RECEIVED";
        this.createdAt = Instant.now();
    }

    public static AgentModelCall failedAttempt(
            Long agentRunId,
            String provider,
            String model,
            PromptEnvelope prompt,
            String callPurpose,
            long latencyMs,
            RuntimeException failure
    ) {
        AgentModelCall call = new AgentModelCall();
        call.agentRunId = agentRunId;
        call.provider = provider;
        call.model = model;
        call.promptVersion = prompt.promptVersion();
        call.schemaVersion = prompt.schemaVersion();
        call.promptHash = prompt.promptHash();
        call.inputTokens = 0;
        call.outputTokens = 0;
        call.callPurpose = callPurpose;
        call.finishReason = "ERROR";
        call.latencyMs = Math.max(0, latencyMs);
        call.responseHash = "0".repeat(64);
        call.status = "FAILED";
        call.failureReason = limit("Provider call failed: " + failure.getClass().getSimpleName());
        call.createdAt = Instant.now();
        return call;
    }

    public void complete() {
        this.status = "VALID";
        this.failureReason = null;
    }

    public void fail(String reason) {
        this.status = "INVALID";
        this.failureReason = limit(reason);
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getPromptHash() {
        return promptHash;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public String getCallPurpose() {
        return callPurpose;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public String getResponseHash() {
        return responseHash;
    }

    public String getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    private static String limit(String reason) {
        return reason == null ? null : reason.substring(0, Math.min(reason.length(), 2_000));
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash model response", ex);
        }
    }
}
