package com.example.codereview.scm;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunCreatedEvent;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.run.AgentStateMachine;
import com.example.codereview.agent.orchestration.AgentScmContext;
import com.example.codereview.agent.orchestration.AgentScmContextRepository;
import com.example.codereview.requirement.RequirementLinkService;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a verified, normalized SCM event into an Agent Run.
 *
 * <p>A run is keyed by a deterministic {@link #triggerKey(NormalizedPullRequestEvent)} (provider +
 * installation + PR number + head SHA):
 * <ul>
 *   <li>The same event is idempotent — it returns the existing run and creates nothing new.</li>
 *   <li>A new head SHA yields a distinct key and a fresh {@code RECEIVED} run, and any older active
 *       run for the same PR is superseded (canceled) so only the latest head is in flight.</li>
 * </ul>
 * The run is created in {@code RECEIVED}, and an {@link AgentRunCreatedEvent} published in the same
 * transaction lets the queue layer schedule the first step atomically (see AgentRunKickoffListener) —
 * the webhook can acknowledge with {@code 202} as soon as this returns; repository download and Agent
 * execution happen later through the outbox.
 */
@Service
public class WebhookAgentRunService {

    /** Outcome of {@link #startFromEvent}: the run plus whether it was newly created. */
    public record StartResult(AgentRun run, boolean created) {
    }

    private final AgentRunRepository agentRunRepository;
    private final AgentStateMachine stateMachine;
    private final AgentScmContextRepository scmContexts;
    private final ApplicationEventPublisher events;
    private final RequirementLinkService requirementLinkService;

    public WebhookAgentRunService(AgentRunRepository agentRunRepository, AgentStateMachine stateMachine,
                                  AgentScmContextRepository scmContexts, ApplicationEventPublisher events,
                                  RequirementLinkService requirementLinkService) {
        this.agentRunRepository = agentRunRepository;
        this.stateMachine = stateMachine;
        this.scmContexts = scmContexts;
        this.events = events;
        this.requirementLinkService = requirementLinkService;
    }

    @Transactional
    public StartResult startFromEvent(NormalizedPullRequestEvent event, ScmInstallation installation) {
        if (installation.getProjectId() == null || installation.getRepositoryId() == null) {
            throw new IllegalStateException("Installation is not bound to a project/repository");
        }
        String triggerKey = triggerKey(event);
        Optional<AgentRun> existing = agentRunRepository.findByTriggerKey(triggerKey);
        if (existing.isPresent()) {
            // Exact replay: same head SHA => same run, nothing new.
            return new StartResult(existing.get(), false);
        }

        AgentRun run = new AgentRun(
                installation.getProjectId(),
                installation.getRepositoryId(),
                null,
                triggerKey,
                event.headSha());
        run = agentRunRepository.save(run);
        scmContexts.save(AgentScmContext.from(run.getId(), event, installation));

        supersedeOlderRuns(event, run.getId());
        // P3 提取器:PR 事件的 title/源分支里的 REQ 号自动挂需求(REQUIRES_NEW 独立小事务 +
        // 异常内吞,webhook 主链路零影响)。
        requirementLinkService.extractQuietly(installation.getProjectId(), "PULL_REQUEST",
                "PR#" + event.pullRequestNumber(), event.title(), event.sourceBranch());
        requirementLinkService.extractQuietly(installation.getProjectId(), "BRANCH",
                event.sourceBranch(), event.sourceBranch());
        // 同事务发布创建事件,由队列层的 BEFORE_COMMIT 监听器排入首步——
        // Run 与首步 outbox 事件要么一起提交、要么都不存在。
        events.publishEvent(new AgentRunCreatedEvent(run.getId(), triggerKey));
        return new StartResult(run, true);
    }

    /** Cancels any non-terminal run for the same PR other than the just-created one. */
    private void supersedeOlderRuns(NormalizedPullRequestEvent event, Long newRunId) {
        for (AgentRun sibling : agentRunRepository.findByTriggerKeyStartingWith(prKey(event))) {
            if (sibling.getId().equals(newRunId) || stateMachine.isTerminal(sibling.getStatus())) {
                continue;
            }
            // Validate through the state machine (every non-terminal state may cancel), then cancel.
            stateMachine.requireTransition(sibling.getStatus(), AgentRunStatus.CANCELED);
            sibling.requestCancellation();
            sibling.advanceTo(AgentRunStatus.CANCELED, sibling.getCurrentStepSequence());
            agentRunRepository.save(sibling);
        }
    }

    /** Deterministic, idempotent run key for a normalized event (includes head SHA). */
    public static String triggerKey(NormalizedPullRequestEvent event) {
        return prKey(event) + event.headSha();
    }

    /** Prefix shared by every run for one PR, independent of head SHA. */
    private static String prKey(NormalizedPullRequestEvent event) {
        return event.provider().name().toLowerCase()
                + ":" + event.installationRef()
                + ":pr:" + event.pullRequestNumber()
                + ":";
    }
}
