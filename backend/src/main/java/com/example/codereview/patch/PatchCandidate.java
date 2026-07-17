package com.example.codereview.patch;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "patch_candidate")
public class PatchCandidate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "agent_run_id", nullable = false) private Long agentRunId;
    @Column(name = "head_sha", nullable = false, length = 80, updatable = false) private String headSha;
    @Column(name = "generator_model", nullable = false, length = 160, updatable = false) private String generatorModel;
    @Column(name = "prompt_version", nullable = false, length = 80, updatable = false) private String promptVersion;
    @Column(name = "patch_content", nullable = false, columnDefinition = "text", updatable = false) private String patchContent;
    @Column(name = "patch_hash", nullable = false, length = 64, updatable = false) private String patchHash;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "validation_reason", columnDefinition = "text") private String validationReason;
    @Column(name = "file_count", nullable = false) private int fileCount;
    @Column(name = "changed_lines", nullable = false) private int changedLines;
    @Column(name = "apply_status", nullable = false, length = 24) private String applyStatus = "NOT_RUN";
    @Column(name = "build_status", nullable = false, length = 24) private String buildStatus = "NOT_RUN";
    @Column(name = "test_status", nullable = false, length = 24) private String testStatus = "NOT_RUN";
    @Column(name = "scan_status", nullable = false, length = 24) private String scanStatus = "NOT_RUN";
    @Column(name = "target_disappeared", nullable = false) private boolean targetDisappeared;
    @Column(name = "validation_result_json", nullable = false, columnDefinition = "text")
    private String validationResultJson = "[]";
    @Column(name = "validation_log", nullable = false, columnDefinition = "text") private String validationLog = "";
    @Column(name = "validated_at") private Instant validatedAt;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "patch_candidate_finding", joinColumns = @JoinColumn(name = "patch_candidate_id"))
    @Column(name = "finding_id", nullable = false)
    private Set<Long> findingIds = new LinkedHashSet<>();
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected PatchCandidate() {}

    public PatchCandidate(Long agentRunId, String headSha, Set<Long> findingIds, String generatorModel,
                          String promptVersion, String patchContent, PatchValidation validation) {
        this.agentRunId = agentRunId;
        this.headSha = required(headSha, "headSha");
        this.findingIds = findingIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(findingIds);
        this.generatorModel = required(generatorModel, "generatorModel");
        this.promptVersion = required(promptVersion, "promptVersion");
        this.patchContent = required(patchContent, "patchContent");
        this.patchHash = sha256(patchContent);
        this.status = validation.valid() ? "SCOPE_VALID" : "REJECTED";
        this.validationReason = validation.reason();
        this.fileCount = validation.files().size();
        this.changedLines = validation.changedLines();
    }

    @PrePersist void onCreate() { if (createdAt == null) createdAt = Instant.now(); }
    public Long getId() { return id; }
    public Long getAgentRunId() { return agentRunId; }
    public String getHeadSha() { return headSha; }
    public String getGeneratorModel() { return generatorModel; }
    public String getPromptVersion() { return promptVersion; }
    public String getPatchContent() { return patchContent; }
    public String getPatchHash() { return patchHash; }
    public String getStatus() { return status; }
    public String getValidationReason() { return validationReason; }
    public int getFileCount() { return fileCount; }
    public int getChangedLines() { return changedLines; }
    public Set<Long> getFindingIds() { return Set.copyOf(findingIds); }
    public Instant getCreatedAt() { return createdAt; }
    public String getApplyStatus() { return applyStatus; }
    public String getBuildStatus() { return buildStatus; }
    public String getTestStatus() { return testStatus; }
    public String getScanStatus() { return scanStatus; }
    public boolean isTargetDisappeared() { return targetDisappeared; }
    public String getValidationResultJson() { return validationResultJson; }
    public String getValidationLog() { return validationLog; }
    public Instant getValidatedAt() { return validatedAt; }

    public void recordSandboxValidation(PatchValidationKind kind, PatchSandboxValidation result) {
        if (!"SCOPE_VALID".equals(status) && !"VALIDATING".equals(status)
                && !"VALIDATED".equals(status) && !"VALIDATION_FAILED".equals(status)) {
            throw new IllegalStateException("patch is not eligible for sandbox validation");
        }
        applyStatus = result.applySucceeded() ? "SUCCEEDED" : result.applyChecked() ? "FAILED" : "NOT_RUN";
        String commandStatus = result.applySucceeded() && result.commandSucceeded() ? "PASSED" : "FAILED";
        switch (kind) {
            case BUILD -> buildStatus = commandStatus;
            case TEST -> testStatus = commandStatus;
            case SCAN -> scanStatus = commandStatus;
        }
        targetDisappeared = targetDisappeared || result.targetDisappeared();
        validationResultJson = appendJson(validationResultJson, result.resultJson());
        validationLog = appendBounded(validationLog, result.boundedLog(), 32_768);
        validatedAt = Instant.now();
        status = isApprovable() ? "VALIDATED" : "VALIDATION_FAILED";
    }

    public boolean isApprovable() { return "SUCCEEDED".equals(applyStatus) && targetDisappeared; }

    private static String appendJson(String current, String value) {
        if (current == null || current.equals("[]")) return "[" + value + "]";
        return current.substring(0, current.length() - 1) + "," + value + "]";
    }
    private static String appendBounded(String current, String value, int maxChars) {
        String combined = (current == null || current.isBlank()) ? value : current + "\n---\n" + value;
        return combined.length() <= maxChars ? combined : combined.substring(combined.length() - maxChars);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
