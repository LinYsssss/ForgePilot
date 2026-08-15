package com.example.codereview.agent.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(
        name = "agent_run",
        uniqueConstraints = @UniqueConstraint(name = "uq_agent_run_trigger_key", columnNames = "triggerKey"),
        indexes = {
                @Index(name = "idx_agent_run_status_updated", columnList = "status,updatedAt"),
                @Index(name = "idx_agent_run_project", columnList = "projectId")
        }
)
public class AgentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long repositoryId;

    private Long pullRequestId;

    @Column(nullable = false, length = 200, updatable = false)
    private String triggerKey;

    @Column(nullable = false, length = 80, updatable = false)
    private String headSha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AgentRunStatus status;

    @Column(nullable = false)
    private int currentStepSequence;

    @Column(nullable = false)
    private boolean cancellationRequested;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected AgentRun() {
    }

    public AgentRun(Long projectId, Long repositoryId, Long pullRequestId, String triggerKey, String headSha) {
        this.projectId = projectId;
        this.repositoryId = repositoryId;
        this.pullRequestId = pullRequestId;
        this.triggerKey = triggerKey;
        this.headSha = headSha;
        this.status = AgentRunStatus.RECEIVED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void advanceTo(AgentRunStatus nextStatus, int stepSequence) {
        this.status = nextStatus;
        this.currentStepSequence = stepSequence;
        this.updatedAt = Instant.now();
    }

    public void requestCancellation() {
        this.cancellationRequested = true;
        this.updatedAt = Instant.now();
    }

    /**
     * 由运维人工发起的终态故障恢复。这是一个**刻意越过**正向 {@link AgentStateMachine} 的动作
     * (状态机把 {@code FAILED}/{@code TIMED_OUT} 视为终态):它把 run 重新打开成
     * {@code RETRY_WAIT},好让被中断的步骤再次走已获许可的 {@code RETRY_WAIT -> step} 路径。
     * 调用方必须先确认当前状态确实可重试。
     */
    public void reopenForRetry() {
        this.status = AgentRunStatus.RETRY_WAIT;
        this.cancellationRequested = false;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getRepositoryId() {
        return repositoryId;
    }

    public Long getPullRequestId() {
        return pullRequestId;
    }

    public String getTriggerKey() {
        return triggerKey;
    }

    public String getHeadSha() {
        return headSha;
    }

    public AgentRunStatus getStatus() {
        return status;
    }

    public int getCurrentStepSequence() {
        return currentStepSequence;
    }

    public boolean isCancellationRequested() {
        return cancellationRequested;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
