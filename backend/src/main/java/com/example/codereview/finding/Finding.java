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

    // ---- P5 生命周期轴(与上面的 pipeline 校验态 status 正交) ----
    @Column(name = "lifecycle_status", nullable = false, length = 32)
    private String lifecycleStatus = "OPEN";

    @Column(name = "assignee_id")
    private Long assigneeId;

    @Column(name = "fix_commit_sha", length = 80)
    private String fixCommitSha;

    @Column(name = "verified_by")
    private Long verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** 自动复审建议(RESOLVED_SUGGESTED/STILL_PRESENT);自动侧只写这里,终态永远人工。 */
    @Column(name = "resolution_suggestion", length = 32)
    private String resolutionSuggestion;

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

    public FindingLifecycle getLifecycle() {
        FindingLifecycle value = FindingLifecycle.fromName(lifecycleStatus);
        return value == null ? FindingLifecycle.OPEN : value;
    }

    public Long getAssigneeId() {
        return assigneeId;
    }

    public String getFixCommitSha() {
        return fixCommitSha;
    }

    public Long getVerifiedBy() {
        return verifiedBy;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public String getResolutionSuggestion() {
        return resolutionSuggestion;
    }

    public void assignTo(Long assigneeId) {
        this.assigneeId = assigneeId;
    }

    /** 流转由 FindingLifecycleService 校验后调用;FIXED 可携带修复 commit。 */
    public void moveLifecycle(FindingLifecycle next, Long operatorId, String fixCommitSha) {
        this.lifecycleStatus = next.name();
        if (next == FindingLifecycle.FIXED && fixCommitSha != null && !fixCommitSha.isBlank()) {
            this.fixCommitSha = fixCommitSha.trim();
        }
        if (next == FindingLifecycle.VERIFIED) {
            this.verifiedBy = operatorId;
            this.verifiedAt = Instant.now();
        }
    }

    /** 自动复审建议;只写建议位,永不改 lifecycle。 */
    public void suggestResolution(String suggestion) {
        this.resolutionSuggestion = suggestion;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
