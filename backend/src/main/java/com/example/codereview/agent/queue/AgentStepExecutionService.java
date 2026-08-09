package com.example.codereview.agent.queue;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.ai.AiCallTransientException;
import com.example.codereview.agent.observability.AgentMetrics;
import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.orchestration.AgentStepResult;
import org.springframework.stereotype.Service;

/**
 * Runs one Agent step in three separate phases.
 *
 * <p>This class deliberately carries no {@code @Transactional} annotation. It used to have one on
 * {@code execute}, which meant a pessimistic row lock and a database connection were held for the
 * entire duration of the step — Git clones, model calls, sandbox runs and SCM write-backs
 * included. Under load that exhausted the connection pool, and any failure rolled back work that
 * had already had external side effects.
 *
 * <pre>
 *   claim      short transaction, takes the execution lease
 *   execute    no transaction at all, lease renewed by heartbeat
 *   complete   short transaction, compare-and-set on the lease token
 * </pre>
 */
@Service
public class AgentStepExecutionService {

    private final AgentStepClaimService claimService;
    private final AgentStepCompletionService completionService;
    private final AgentStepLeaseHeartbeat heartbeat;
    private final AgentStepHandler handler;
    private final AgentMetrics metrics;

    public AgentStepExecutionService(
            AgentStepClaimService claimService,
            AgentStepCompletionService completionService,
            AgentStepLeaseHeartbeat heartbeat,
            AgentStepHandler handler,
            AgentMetrics metrics
    ) {
        this.claimService = claimService;
        this.completionService = completionService;
        this.heartbeat = heartbeat;
        this.handler = handler;
        this.metrics = metrics;
    }

    public ExecutionOutcome execute(AgentStepMessage message) {
        AgentStepClaim claim = claimService.claim(message);
        if (!claim.isClaimed()) {
            return claim.rejection();
        }

        AgentStepExecutionContext context = claim.context();
        long startedNanos = System.nanoTime();
        ExecutionOutcome outcome = runClaimed(claim, context, message);
        metrics.recordStep(context.stepType(), outcome.name(), (System.nanoTime() - startedNanos) / 1_000_000);
        return outcome;
    }

    private ExecutionOutcome runClaimed(
            AgentStepClaim claim, AgentStepExecutionContext context, AgentStepMessage message) {
        try {
            if (context.cancellationRequested()) {
                throw new AgentStepExecutionException(
                        AgentFailureType.CANCELED, "Agent run cancellation requested before step execution");
            }
            // No transaction is open here. Whatever the handler does — network, subprocess, model
            // call — it does without holding a row lock.
            AgentStepResult result =
                    heartbeat.runWithRenewal(claim.stepId(), claim.executionToken(), () -> handler.execute(context));
            return completionService.complete(context, claim.executionToken(), message, result);
        } catch (AgentStepExecutionException exception) {
            return completionService.fail(
                    context, claim.executionToken(), message, exception.getFailureType(), exception.getMessage());
        } catch (AiCallTransientException exception) {
            // 首次真实运行即暴露(run14,2026-08-08):LangChain4j 客户端已把 429/5xx/超时正确分类为
            // AiCallTransientException,但它穿透到下面的兜底 catch 后被按 INTERNAL_ERROR 终态处理,
            // RETRYABLE_PROVIDER_ERROR 与 scheduleRetry 全程闲置——分类与重试之间缺的就是这一跳。
            // provider 一次瞬态抖动不该杀死整个 Run,在此映射为可重试的 provider 错误。
            return completionService.fail(
                    context,
                    claim.executionToken(),
                    message,
                    AgentFailureType.RETRYABLE_PROVIDER_ERROR,
                    failureMessage(exception));
        } catch (RuntimeException exception) {
            return completionService.fail(
                    context,
                    claim.executionToken(),
                    message,
                    AgentFailureType.INTERNAL_ERROR,
                    failureMessage(exception));
        }
    }

    private String failureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getName() : message;
    }

    public enum ExecutionOutcome {
        SUCCEEDED,
        RETRY_SCHEDULED,
        FAILED,
        DUPLICATE_IGNORED,
        STALE_OR_ACTIVE_IGNORED,
        /** The lease was reclaimed mid-flight; this worker's result was discarded. */
        LEASE_LOST
    }
}
