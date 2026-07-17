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
