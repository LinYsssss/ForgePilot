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
@Table(name = "agent_finding")
public class Finding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_run_id", nullable = false)
    private Long agentRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private FindingSeverity severity;

    @Column(nullable = false, length = 160)
    private String category;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "file_path", length = 1024)
    private String filePath;

    @Column(name = "line_start")
    private Integer lineStart;

    @Column(name = "line_end")
    private Integer lineEnd;

    @Column(length = 512)
    private String symbol;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(length = 64)
    private String fingerprint;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Finding() {
    }

    public Finding(
            Long agentRunId,
            FindingSeverity severity,
            String category,
            String title,
            String description,
            String filePath,
            Integer lineStart,
            Integer lineEnd,
            String symbol,
            String status) {
        this.agentRunId = agentRunId;
        this.severity = severity;
        this.category = category;
        this.title = title;
        this.description = description;
        this.filePath = filePath;
        this.lineStart = lineStart;
        this.lineEnd = lineEnd;
        this.symbol = symbol;
        this.status = status;
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

    public Long getAgentRunId() {
        return agentRunId;
    }

    public FindingSeverity getSeverity() {
        return severity;
    }

    public String getCategory() {
        return category;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getFilePath() {
        return filePath;
    }

    public Integer getLineStart() {
        return lineStart;
    }

    public Integer getLineEnd() {
        return lineEnd;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getStatus() {
        return status;
    }

    public void applyVerification(String fingerprint, boolean accepted, String rejectionReason) {
        if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("fingerprint must be lowercase SHA-256");
        }
        if (!accepted && (rejectionReason == null || rejectionReason.isBlank())) {
            throw new IllegalArgumentException("rejectionReason is required");
        }
        this.fingerprint = fingerprint;
        this.status = accepted ? "verified" : "rejected";
        this.rejectionReason = accepted ? null : rejectionReason;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
