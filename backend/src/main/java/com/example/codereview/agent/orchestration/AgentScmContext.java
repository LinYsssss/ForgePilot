package com.example.codereview.agent.orchestration;

import com.example.codereview.scm.NormalizedPullRequestEvent;
import com.example.codereview.scm.ScmInstallation;
import com.example.codereview.scm.ScmProviderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "agent_scm_context", uniqueConstraints = @UniqueConstraint(
        name = "uq_agent_scm_context_run", columnNames = "agentRunId"
))
public class AgentScmContext {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false) private Long agentRunId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private ScmProviderType provider;
    @Column(nullable = false, updatable = false) private Long installationId;
    @Column(nullable = false, length = 255) private String repositoryFullName;
    @Column(nullable = false, length = 512) private String repositoryCloneUrl;
    @Column(nullable = false) private int pullRequestNumber;
    @Column(nullable = false, length = 80) private String baseSha;
    @Column(nullable = false, length = 80) private String headSha;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected AgentScmContext() {}

    private AgentScmContext(Long agentRunId, ScmProviderType provider, Long installationId,
                            String repositoryFullName, String repositoryCloneUrl,
                            int pullRequestNumber, String baseSha, String headSha) {
        this.agentRunId = agentRunId;
        this.provider = provider;
        this.installationId = installationId;
        this.repositoryFullName = repositoryFullName;
        this.repositoryCloneUrl = repositoryCloneUrl;
        this.pullRequestNumber = pullRequestNumber;
        this.baseSha = baseSha;
        this.headSha = headSha;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public static AgentScmContext from(Long runId, NormalizedPullRequestEvent event, ScmInstallation installation) {
        if (runId == null || event == null || installation == null || installation.getId() == null) {
            throw new IllegalArgumentException("run, event, and persisted installation are required");
        }
        return new AgentScmContext(
                runId, event.provider(), installation.getId(), event.repositoryFullName(),
                event.repositoryCloneUrl(), event.pullRequestNumber(), event.baseSha(), event.headSha()
        );
    }

    public void requireHead(String expected) {
        if (!headSha.equals(expected)) {
            throw new IllegalStateException("SCM context head SHA does not match Agent run");
        }
    }

    public Long getId() { return id; }
    public Long getAgentRunId() { return agentRunId; }
    public ScmProviderType getProvider() { return provider; }
    public Long getInstallationId() { return installationId; }
    public String getRepositoryFullName() { return repositoryFullName; }
    public String getRepositoryCloneUrl() { return repositoryCloneUrl; }
    public int getPullRequestNumber() { return pullRequestNumber; }
    public String getBaseSha() { return baseSha; }
    public String getHeadSha() { return headSha; }
    public Instant getCreatedAt() { return createdAt; }
}
