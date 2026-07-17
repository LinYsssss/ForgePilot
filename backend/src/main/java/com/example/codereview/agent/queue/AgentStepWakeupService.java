package com.example.codereview.agent.queue;

import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.run.AgentStep;
import com.example.codereview.agent.run.AgentStepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentStepWakeupService {
    private final AgentRunRepository runs;
    private final AgentStepRepository steps;
    private final AgentRunTransitionEnqueuer enqueuer;

    public AgentStepWakeupService(AgentRunRepository runs, AgentStepRepository steps,
                                  AgentRunTransitionEnqueuer enqueuer) {
        this.runs = runs;
        this.steps = steps;
        this.enqueuer = enqueuer;
    }

    @Transactional
    public void wakeWaiting(Long agentRunId) {
        var run = runs.findById(agentRunId).orElseThrow(() -> new IllegalArgumentException("agent run not found"));
        if (run.getStatus() != AgentRunStatus.WAITING_APPROVAL) return;
        AgentStep step = steps.findForUpdate(agentRunId, run.getCurrentStepSequence())
                .orElseThrow(() -> new IllegalArgumentException("waiting approval step not found"));
        if (step.isSucceeded()) step.reopenForExternalWakeup();
        enqueuer.enqueue(step, "approval-wakeup:" + agentRunId + ":" + step.getSequenceNo());
    }
}
