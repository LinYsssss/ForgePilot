package com.example.codereview.finding;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingRepository extends JpaRepository<Finding, Long> {
    List<Finding> findByAgentRunIdOrderByIdAsc(Long agentRunId);

    /** 给 API 用的分页版本;无界的那个留给内部调用方。 */
    org.springframework.data.domain.Page<Finding> findByAgentRunIdOrderByIdAsc(
            Long agentRunId, org.springframework.data.domain.Pageable pageable);

    /** P5 质量中心:项目全量(经 agent_run 归属),可按生命周期过滤。 */
    @org.springframework.data.jpa.repository.Query("""
            select f from Finding f, com.example.codereview.agent.run.AgentRun r,
                    com.example.codereview.agent.orchestration.AgentScmContext c
            where f.agentRunId = r.id and c.agentRunId = r.id
              and r.projectId = :projectId
              and (:lifecycle is null or f.lifecycleStatus = :lifecycle)
            order by f.id desc""")
    org.springframework.data.domain.Page<Finding> findByProjectAndLifecycle(
            Long projectId, String lifecycle, org.springframework.data.domain.Pageable pageable);

    List<Finding> findByAgentRunIdIn(List<Long> agentRunIds);

    @org.springframework.data.jpa.repository.Query("""
            select f.fingerprint from Finding f
            where f.agentRunId = :runId and f.status = 'verified' and f.fingerprint is not null""")
    List<String> findVerifiedFingerprintsByAgentRunId(Long runId);

    /** One bounded join for earlier runs of the same installation + PR; avoids run-by-run N+1 reads. */
    @org.springframework.data.jpa.repository.Query("""
            select f from Finding f, com.example.codereview.agent.orchestration.AgentScmContext c
            where f.agentRunId = c.agentRunId
              and c.agentRunId <> :currentRunId
              and c.installationId = :installationId
              and c.pullRequestNumber = :pullRequestNumber
              and (c.createdAt < :currentCreatedAt
                   or (c.createdAt = :currentCreatedAt and c.agentRunId < :currentRunId))
              and f.status = 'verified'
              and f.fingerprint is not null
              and f.lifecycleStatus not in ('REJECTED', 'CLOSED')
            order by f.id asc""")
    List<Finding> findHistoricalActiveForPullRequest(
            Long currentRunId, Long installationId, int pullRequestNumber, java.time.Instant currentCreatedAt);
}
