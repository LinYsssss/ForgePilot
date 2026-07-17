package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.orchestration.AgentStepExecutor;
import com.example.codereview.agent.orchestration.AgentStepResult;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.patch.PatchApprovalDecision;
import com.example.codereview.patch.PatchApprovalRepository;
import com.example.codereview.patch.PatchCandidateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class WaitingApprovalStepExecutor implements AgentStepExecutor {

    private final PatchCandidateRepository patches;
    private final PatchApprovalRepository approvals;

    @Autowired
    public WaitingApprovalStepExecutor(
            PatchCandidateRepository patches,
            PatchApprovalRepository approvals
    ) {
        this.patches = patches;
        this.approvals = approvals;
    }

    public WaitingApprovalStepExecutor() {
        this.patches = null;
        this.approvals = null;
    }

    @Override
    public AgentRunStatus state() { return AgentRunStatus.WAITING_APPROVAL; }

    @Override
    public AgentStepResult execute(AgentStepExecutionContext context) {
        if (context.stepType() != state()) {
            throw new IllegalArgumentException("Executor state does not match context");
        }
        if (patches == null) {
            return AgentStepResult.checkpoint(state());
        }
        var patch = patches.findByAgentRunIdOrderByIdAsc(context.agentRunId()).stream()
                .max(java.util.Comparator.comparing(com.example.codereview.patch.PatchCandidate::getId))
                .orElse(null);
        if (patch == null) {
            return advance("no patch candidate");
        }
        var decision = approvals.findByPatchCandidateIdOrderByDecidedAtDesc(patch.getId()).stream()
                .findFirst().map(value -> value.getDecision()).orElse(null);
        if (decision == null) {
            return new AgentStepResult(
                    "agent-step-result-v1", state(), AgentStepResult.Disposition.WAITING_EXTERNAL,
                    null, java.util.Map.of("patchId", patch.getId(), "approval", "PENDING")
            );
        }
        return advance(decision == PatchApprovalDecision.APPROVED ? "approved" : "rejected");
    }

    private AgentStepResult advance(String reason) {
        return new AgentStepResult(
                "agent-step-result-v1", state(), AgentStepResult.Disposition.ADVANCE,
                AgentRunStatus.PUBLISHING_RESULT, java.util.Map.of("approval", reason)
        );
    }
}
