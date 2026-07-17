package com.example.codereview.agent.orchestration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "agent_analysis_context", uniqueConstraints = @UniqueConstraint(
        name = "uq_agent_analysis_context_run", columnNames = "agentRunId"
))
public class AgentAnalysisContext {

    private static final int MAX_JSON_BYTES = 262_144;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long agentRunId;

    @Column(nullable = false, length = 40, updatable = false)
    private String schemaVersion;

    @Column(nullable = false, length = 80, updatable = false)
    private String headSha;

    @Column(length = 80)
    private String baseSha;

    @Column(length = 500)
    private String archiveRef;

    @Column(columnDefinition = "text")
    private String repositoryProfileJson;

    @Column(columnDefinition = "text")
    private String changedDiff;

    @Column(columnDefinition = "text")
    private String changeSetJson;

    @Column(columnDefinition = "text")
    private String retrievedContextJson;

    @Column(columnDefinition = "text")
    private String findingsJson;

    @Column(columnDefinition = "text")
    private String gateJson;

    @Column(nullable = false, length = 40)
    private String stage;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected AgentAnalysisContext() {
    }

    public AgentAnalysisContext(Long agentRunId, String headSha) {
        this.agentRunId = Objects.requireNonNull(agentRunId, "agentRunId");
        this.headSha = requireText(headSha, "headSha");
        this.schemaVersion = "agent-analysis-v1";
        this.stage = "CREATED";
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void repositoryPrepared(
            String archiveRef,
            String baseSha,
            String repositoryProfileJson,
            String changedDiff
    ) {
        this.archiveRef = requireText(archiveRef, "archiveRef");
        this.baseSha = requireText(baseSha, "baseSha");
        this.repositoryProfileJson = bounded(repositoryProfileJson, "repository profile");
        this.changedDiff = bounded(changedDiff, "changed diff");
        advance("REPOSITORY_PREPARED");
    }

    public void changeAnalyzed(String changeSetJson) {
        requireStage("REPOSITORY_PREPARED", "repository must be prepared before change analysis");
        this.changeSetJson = bounded(changeSetJson, "change set");
        advance("CHANGE_ANALYZED");
    }

    public void contextRetrieved(String retrievedContextJson) {
        requireStage("CHANGE_ANALYZED", "change must be analyzed before context retrieval");
        this.retrievedContextJson = bounded(retrievedContextJson, "retrieved context");
        advance("CONTEXT_RETRIEVED");
    }

    public void findingsVerified(String findingsJson, String gateJson) {
        requireStage("CONTEXT_RETRIEVED", "context must be retrieved before finding verification");
        this.findingsJson = bounded(findingsJson, "findings");
        this.gateJson = bounded(gateJson, "gate");
        advance("FINDINGS_VERIFIED");
    }

    public void requireHead(String expectedHeadSha) {
        if (!headSha.equals(expectedHeadSha)) {
            throw new IllegalStateException("analysis context head SHA does not match Agent run");
        }
    }

    private void requireStage(String expected, String message) {
        if (!expected.equals(stage)) {
            throw new IllegalStateException(message);
        }
    }

    private void advance(String next) {
        this.stage = next;
        this.updatedAt = Instant.now();
    }

    private String bounded(String json, String label) {
        String value = Objects.requireNonNull(json, label);
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException(label + " exceeds " + MAX_JSON_BYTES + " bytes");
        }
        return value;
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    public String getSchemaVersion() { return schemaVersion; }
    public String getHeadSha() { return headSha; }
    public String getArchiveRef() { return archiveRef; }
    public String getBaseSha() { return baseSha; }
    public String getRepositoryProfileJson() { return repositoryProfileJson; }
    public String getChangedDiff() { return changedDiff; }
    public String getChangeSetJson() { return changeSetJson; }
    public String getRetrievedContextJson() { return retrievedContextJson; }
    public String getFindingsJson() { return findingsJson; }
    public String getGateJson() { return gateJson; }
    public String getStage() { return stage; }
}
