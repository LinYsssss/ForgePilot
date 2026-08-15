package com.example.codereview.agent.queue;

import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.queue.AgentStepExecutionService.ExecutionOutcome;
import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.run.AgentStateMachine;
import com.example.codereview.agent.run.AgentStep;
import com.example.codereview.agent.run.AgentStepRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 步骤执行的第一阶段:判定这条消息是否可以执行,并把租约拿下。
 *
 * <p>这是**唯一**还在用悲观行锁的地方,而且只持有「检查尝试规则 + 盖上 token」那几毫秒——
 * 绝不会跨越一次 Git clone 或一次模型调用。
 */
@Service
public class AgentStepClaimService {

    private final AgentRunRepository runs;
    private final AgentStepRepository steps;
    private final AgentStateMachine stateMachine;
    private final Clock clock;
    private final Duration leaseDuration;
    private final String workerId;

    @Autowired
    public AgentStepClaimService(
            AgentRunRepository runs,
            AgentStepRepository steps,
            AgentStateMachine stateMachine,
            @Value("${app.agent.step.lease-duration:3m}") Duration leaseDuration
    ) {
        this(runs, steps, stateMachine, Clock.systemUTC(), leaseDuration, defaultWorkerId());
    }

    public AgentStepClaimService(
            AgentRunRepository runs,
            AgentStepRepository steps,
            AgentStateMachine stateMachine,
            Clock clock,
            Duration leaseDuration,
            String workerId
    ) {
        this.runs = runs;
        this.steps = steps;
        this.stateMachine = stateMachine;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
        this.workerId = workerId;
    }

    @Transactional
    public AgentStepClaim claim(AgentStepMessage message) {
        AgentRun run = runs.findById(message.agentRunId())
                .orElseThrow(() -> new IllegalArgumentException("Agent run not found: " + message.agentRunId()));
        AgentStep step = steps.findForUpdate(message.agentRunId(), message.sequenceNo())
                .orElseThrow(() -> new IllegalArgumentException("Agent step not found: " + message.identity()));

        if (step.isSucceeded()) {
            return AgentStepClaim.rejected(ExecutionOutcome.DUPLICATE_IGNORED);
        }
        if (!step.acceptsAttempt(message.attempt())) {
            // 要么已在别人的租约下 RUNNING,要么这个尝试号已经过期。
            return AgentStepClaim.rejected(ExecutionOutcome.STALE_OR_ACTIVE_IGNORED);
        }

        activateRetryRun(run, step);
        String executionToken = UUID.randomUUID().toString();
        step.start(message.attempt());
        step.leaseTo(executionToken, workerId, clock.instant().plus(leaseDuration));
        steps.saveAndFlush(step);

        return AgentStepClaim.claimed(context(run, step, message), executionToken, step.getId());
    }

    private void activateRetryRun(AgentRun run, AgentStep step) {
        if (run.getStatus() != AgentRunStatus.RETRY_WAIT) {
            if (run.getStatus() != step.getStepType()) {
                throw new IllegalStateException(
                        "Agent run status " + run.getStatus() + " does not match step " + step.getStepType());
            }
            return;
        }
        stateMachine.requireTransition(run.getStatus(), step.getStepType());
        run.advanceTo(step.getStepType(), step.getSequenceNo());
    }

    private AgentStepExecutionContext context(AgentRun run, AgentStep step, AgentStepMessage message) {
        return new AgentStepExecutionContext(
                run.getId(),
                step.getId(),
                run.getProjectId(),
                run.getRepositoryId(),
                run.getPullRequestId(),
                run.getHeadSha(),
                step.getStepType(),
                step.getSequenceNo(),
                message.attempt(),
                message.traceId(),
                run.isCancellationRequested()
        );
    }

    private static String defaultWorkerId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException ex) {
            host = "unknown-host";
        }
        return host + "/" + ProcessHandle.current().pid();
    }
}
