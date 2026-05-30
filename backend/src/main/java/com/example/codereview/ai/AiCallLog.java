package com.example.codereview.ai;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ai_call_log")
public class AiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;

    private Long taskId;

    @Column(nullable = false, length = 64)
    private String requestType;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(nullable = false, length = 128)
    private String model;

    @Column(nullable = false)
    private int promptChars;

    @Column(nullable = false)
    private int responseChars;

    @Column(nullable = false)
    private long latencyMs;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(columnDefinition = "text")
    private String errorMessage;

    @Column(nullable = false)
    private Instant createdAt;

    protected AiCallLog() {
    }

    public AiCallLog(Long projectId, Long taskId, String requestType, String provider, String model,
                     int promptChars, int responseChars, long latencyMs, String status, String errorMessage) {
        this.projectId = projectId;
        this.taskId = taskId;
        this.requestType = requestType;
        this.provider = provider;
        this.model = model;
        this.promptChars = promptChars;
        this.responseChars = responseChars;
        this.latencyMs = latencyMs;
        this.status = status;
        this.errorMessage = errorMessage;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public String getRequestType() {
        return requestType;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public int getPromptChars() {
        return promptChars;
    }

    public int getResponseChars() {
        return responseChars;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
