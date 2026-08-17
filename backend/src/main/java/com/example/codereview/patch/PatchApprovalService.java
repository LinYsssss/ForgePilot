package com.example.codereview.patch;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.ProjectAuthorization;
import com.example.codereview.project.ProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Objects;

@Service
public class PatchApprovalService {
    private final PatchCandidateRepository patches; private final PatchApprovalRepository approvals;
    private final AgentRunRepository runs; private final ProjectService projects;
    private final ProjectAuthorization projectAuthorization;
    private final com.example.codereview.agent.queue.AgentStepWakeupService wakeup;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.codereview.ai.langchain4j.LangChain4jRolloutPolicy rolloutPolicy;

    @org.springframework.beans.factory.annotation.Autowired
    public PatchApprovalService(PatchCandidateRepository patches, PatchApprovalRepository approvals,
                                AgentRunRepository runs, ProjectService projects,
                                ProjectAuthorization projectAuthorization,
                                com.example.codereview.agent.queue.AgentStepWakeupService wakeup) {
        this.patches = patches; this.approvals = approvals; this.runs = runs; this.projects = projects;
        this.projectAuthorization = projectAuthorization;
        this.wakeup = wakeup;
    }

    public PatchApprovalService(PatchCandidateRepository patches, PatchApprovalRepository approvals,
                                AgentRunRepository runs, ProjectService projects,
                                ProjectAuthorization projectAuthorization) {
        this(patches, approvals, runs, projects, projectAuthorization, null);
    }
    @Transactional
    public PatchApproval decide(Long projectId, Long patchId, Long agentRunId, Long approverId,
                                String currentHeadSha, PatchApprovalDecision decision, String comment) {
        if (rolloutPolicy != null && rolloutPolicy.shadow()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "影子模式下补丁审批已停用");
        }
        // 补丁审批是最高权限动作(P1a 矩阵):仅 LEADER。
        projectAuthorization.requireWrite(projectId, approverId);
        PatchCandidate patch = patches.findById(patchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATCH_NOT_FOUND, "补丁候选不存在"));
        AgentRun run = runs.findById(agentRunId).orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent Run 不存在"));
        if (!projectId.equals(run.getProjectId()) || !agentRunId.equals(patch.getAgentRunId())) {
            throw new BusinessException(ErrorCode.PATCH_NOT_FOUND, "补丁候选不属于该项目的 Agent Run");
        }
        // head 变了说明补丁已过期:这是可预期的业务分支(前端会显示 stale),不是系统故障。
        // getHeadSha 可能为 null,用 Objects.equals 避免 NPE 被兜成 500。
        if (!Objects.equals(run.getHeadSha(), currentHeadSha) || !Objects.equals(patch.getHeadSha(), currentHeadSha)) {
            throw new BusinessException(ErrorCode.PATCH_STALE, "补丁已过期(head 已变更),请刷新后重试");
        }
        if (decision == PatchApprovalDecision.APPROVED && !patch.isApprovable()) {
            throw new BusinessException(ErrorCode.PATCH_APPROVAL_CONFLICT, "该补丁未通过校验,不可批准");
        }
        var existing = approvals.findByPatchCandidateIdAndApproverId(patchId, approverId);
        if (existing.isPresent()) {
            if (existing.get().getDecision() != decision) throw new BusinessException(ErrorCode.PATCH_APPROVAL_CONFLICT, "审批结论不可更改");
            return existing.get();
        }
        PatchApproval saved = approvals.save(new PatchApproval(patchId, approverId, decision,
                patch.getPatchHash(), patch.getHeadSha(), bounded(comment, 2000)));
        if (wakeup != null) wakeup.wakeWaiting(agentRunId);
        return saved;
    }
    public PatchListView list(Long projectId, Long agentRunId, Long userId) {
        projects.getRequired(projectId, userId);
        AgentRun run = runs.findById(agentRunId).orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent Run 不存在"));
        if (!projectId.equals(run.getProjectId())) throw new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent Run 不属于该项目");
        return new PatchListView(run.getHeadSha(), patches.findByAgentRunIdOrderByIdAsc(agentRunId));
    }

    public record PatchListView(String runHeadSha, List<PatchCandidate> patches) {}
    private static String bounded(String value, int limit) {
        if (value == null) return ""; return value.length() <= limit ? value : value.substring(0, limit);
    }
}
