package com.example.codereview.patch;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.project.ProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class PatchApprovalService {
    private final PatchCandidateRepository patches; private final PatchApprovalRepository approvals;
    private final AgentRunRepository runs; private final ProjectService projects;
    private final com.example.codereview.agent.queue.AgentStepWakeupService wakeup;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.codereview.ai.langchain4j.LangChain4jRolloutPolicy rolloutPolicy;

    @org.springframework.beans.factory.annotation.Autowired
    public PatchApprovalService(PatchCandidateRepository patches, PatchApprovalRepository approvals,
                                AgentRunRepository runs, ProjectService projects,
                                com.example.codereview.agent.queue.AgentStepWakeupService wakeup) {
        this.patches = patches; this.approvals = approvals; this.runs = runs; this.projects = projects;
        this.wakeup = wakeup;
    }

    public PatchApprovalService(PatchCandidateRepository patches, PatchApprovalRepository approvals,
                                AgentRunRepository runs, ProjectService projects) {
        this(patches, approvals, runs, projects, null);
    }
    @Transactional
    public PatchApproval decide(Long projectId, Long patchId, Long agentRunId, Long approverId,
                                String currentHeadSha, PatchApprovalDecision decision, String comment) {
        if (rolloutPolicy != null && rolloutPolicy.shadow()) {
            throw new IllegalStateException("Patch approval is disabled in LangChain4j shadow mode");
        }
        projects.getRequired(projectId, approverId);
        PatchCandidate patch = patches.findById(patchId)
                .orElseThrow(() -> new IllegalArgumentException("patch candidate not found"));
        AgentRun run = runs.findById(agentRunId).orElseThrow(() -> new IllegalArgumentException("agent run not found"));
        if (!projectId.equals(run.getProjectId()) || !agentRunId.equals(patch.getAgentRunId())) {
            throw new IllegalArgumentException("patch candidate does not belong to project agent run");
        }
        if (!run.getHeadSha().equals(currentHeadSha) || !patch.getHeadSha().equals(currentHeadSha)) {
            throw new IllegalArgumentException("stale head SHA");
        }
        if (decision == PatchApprovalDecision.APPROVED && !patch.isApprovable()) {
            throw new IllegalStateException("patch is not eligible for approval");
        }
        var existing = approvals.findByPatchCandidateIdAndApproverId(patchId, approverId);
        if (existing.isPresent()) {
            if (existing.get().getDecision() != decision) throw new IllegalStateException("approval decision is immutable");
            return existing.get();
        }
        PatchApproval saved = approvals.save(new PatchApproval(patchId, approverId, decision,
                patch.getPatchHash(), patch.getHeadSha(), bounded(comment, 2000)));
        if (wakeup != null) wakeup.wakeWaiting(agentRunId);
        return saved;
    }
    public List<PatchCandidate> list(Long projectId, Long agentRunId, Long userId) {
        projects.getRequired(projectId, userId);
        AgentRun run = runs.findById(agentRunId).orElseThrow(() -> new IllegalArgumentException("agent run not found"));
        if (!projectId.equals(run.getProjectId())) throw new IllegalArgumentException("agent run does not belong to project");
        return patches.findByAgentRunIdOrderByIdAsc(agentRunId);
    }
    private static String bounded(String value, int limit) {
        if (value == null) return ""; return value.length() <= limit ? value : value.substring(0, limit);
    }
}
