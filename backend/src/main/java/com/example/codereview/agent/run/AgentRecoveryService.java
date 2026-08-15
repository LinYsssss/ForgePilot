package com.example.codereview.agent.run;

import com.example.codereview.agent.observability.AgentMetrics;
import com.example.codereview.agent.queue.AgentStepPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AgentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(AgentRecoveryService.class);
    private static final String INTERRUPTION_REASON = "Agent worker restart interrupted this step";
    private static final Set<AgentRunStatus> RECOVERABLE_STATUSES = Set.copyOf(EnumSet.of(
            AgentRunStatus.PREPARING_REPOSITORY,
            AgentRunStatus.ANALYZING_CHANGE,
            AgentRunStatus.PLANNING,
            AgentRunStatus.EXECUTING_TOOLS,
            AgentRunStatus.RETRIEVING_CONTEXT,
            AgentRunStatus.VERIFYING_FINDINGS,
            AgentRunStatus.GENERATING_PATCH,
            AgentRunStatus.VALIDATING_PATCH,
            AgentRunStatus.PUBLISHING_RESULT
    ));

    private final AgentStepRepository steps;
    private final AgentStepPublisher publisher;
    private final AgentMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration staleThreshold;
    private final int batchSize;
    private final boolean enabled;

    @Autowired
    public AgentRecoveryService(
            AgentStepRepository steps,
            AgentStepPublisher publisher,
            AgentMetrics metrics,
            PlatformTransactionManager transactionManager,
            @Value("${app.agent.recovery.stale-threshold:5m}") Duration staleThreshold,
            @Value("${app.agent.recovery.batch-size:100}") int batchSize,
            @Value("${app.agent.recovery.enabled:true}") boolean enabled
    ) {
        this(steps, publisher, metrics, transactionManager, Clock.systemUTC(), staleThreshold, batchSize, enabled);
    }

    AgentRecoveryService(
            AgentStepRepository steps,
            AgentStepPublisher publisher,
            AgentMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Duration staleThreshold,
            int batchSize,
            boolean enabled
    ) {
        if (staleThreshold.isNegative() || staleThreshold.isZero()) {
            throw new IllegalArgumentException("staleThreshold must be positive");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.steps = steps;
        this.publisher = publisher;
        this.metrics = metrics;
        this.clock = clock;
        this.staleThreshold = staleThreshold;
        this.batchSize = batchSize;
        this.enabled = enabled;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        if (!enabled) {
            return;
        }
        int recovered = recoverStaleRuns();
        if (recovered > 0) {
            log.info("Recovered {} interrupted Agent step(s) after startup", recovered);
        }
    }

    /**
     * 恢复不能只在启动时跑:如果 worker 死了而进程还活着,那个步骤会一直挂在 RUNNING,
     * 直到下次重启才被发现。如今步骤在执行期间会持续续租,因此「租约没再续」是一个真正的
     * 存活性信号,而不是「这行多久没被动过」式的猜测。
     *
     * <p>只有 {@code app.agent.scheduling.enabled} 打开时才生效——正是它注册了调度基础设施。
     */
    @Scheduled(
            fixedDelayString = "${app.agent.recovery.interval-ms:60000}",
            initialDelayString = "${app.agent.recovery.initial-delay-ms:60000}")
    public void recoverPeriodically() {
        if (!enabled) {
            return;
        }
        try {
            int recovered = recoverStaleRuns();
            if (recovered > 0) {
                log.warn("Watchdog recovered {} stalled Agent step(s)", recovered);
            }
        } catch (RuntimeException ex) {
            // 因为一次瞬时错误就把 watchdog 丢掉,等于把它本来要防的「run 卡死」原样请回来。
            log.warn("Agent step recovery pass failed; will retry on the next tick", ex);
        }
    }

    public int recoverStaleRuns() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(staleThreshold);
        List<Long> candidates = steps.findStaleRunningIds(
                AgentStepStatus.RUNNING,
                cutoff,
                now,
                RECOVERABLE_STATUSES,
                PageRequest.of(0, batchSize)
        );
        int recovered = 0;
        for (Long stepId : candidates) {
            Boolean claimed = transactions.execute(status -> recoverOne(stepId, cutoff, now));
            if (Boolean.TRUE.equals(claimed)) {
                recovered++;
            }
        }
        metrics.runsRecovered(recovered);
        return recovered;
    }

    private boolean recoverOne(Long stepId, Instant cutoff, Instant now) {
        int claimed = steps.interruptIfStale(
                stepId,
                AgentStepStatus.RUNNING,
                AgentStepStatus.INTERRUPTED,
                cutoff,
                now,
                INTERRUPTION_REASON,
                RECOVERABLE_STATUSES
        );
        if (claimed != 1) {
            return false;
        }
        AgentStep interrupted = steps.findById(stepId)
                .orElseThrow(() -> new IllegalStateException("Claimed Agent step disappeared: " + stepId));
        publisher.republishInterrupted(interrupted);
        return true;
    }
}
