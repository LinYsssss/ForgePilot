package com.example.codereview.finding;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.orchestration.AgentScmContextRepository;
import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.api.PageResponse;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.ProjectAuthorization;
import com.example.codereview.member.ProjectRole;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finding 生命周期闭环(P5,design §8)。角色边界:确认/驳回/验证/关闭 = REVIEWER 或 LEADER;
 * 开始修复/标记已修复 = 被指派人或 LEADER;指派 = LEADER。范围:仅 agent(PR)findings;
 * 交互式报告保持报告制不入闭环。
 */
@Service
public class FindingLifecycleService {

    private static final Set<ProjectRole> REVIEW_ROLES = Set.of(ProjectRole.LEADER, ProjectRole.REVIEWER);

    private final FindingRepository findings;
    private final FindingEvidenceRepository evidences;
    private final FindingDecisionRepository decisions;
    private final AgentRunRepository runs;
    private final AgentScmContextRepository scmContexts;
    private final ProjectAuthorization projectAuthorization;

    public FindingLifecycleService(FindingRepository findings, FindingEvidenceRepository evidences,
                                   FindingDecisionRepository decisions, AgentRunRepository runs,
                                   AgentScmContextRepository scmContexts,
                                   ProjectAuthorization projectAuthorization) {
        this.findings = findings;
        this.evidences = evidences;
        this.decisions = decisions;
        this.runs = runs;
        this.scmContexts = scmContexts;
        this.projectAuthorization = projectAuthorization;
    }

    public PageResponse<AgentFindingDtos.AgentFindingResponse> listByProject(
            Long projectId, Long userId, String lifecycle, Integer page, Integer size) {
        projectAuthorization.requireRead(projectId, userId);
        String filter = lifecycle == null || lifecycle.isBlank()
                ? null : lifecycle.trim().toUpperCase(Locale.ROOT);
        if (filter != null && FindingLifecycle.fromName(filter) == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "未知的生命周期状态");
        }
        PageRequest pageRequest = PageRequest.of(PageResponse.sanitizePage(page), PageResponse.sanitizeSize(size));
        return PageResponse.from(
                findings.findByProjectAndLifecycle(projectId, filter, pageRequest),
                finding -> AgentFindingDtos.AgentFindingResponse.from(
                        finding,
                        evidences.findByFindingIdOrderByIdAsc(finding.getId()),
                        decisions.findByFindingIdOrderByIdAsc(finding.getId()).stream()
                                .reduce((first, second) -> second).orElse(null)));
    }

    @Transactional
    public AgentFindingDtos.AgentFindingResponse transition(Long projectId, Long userId, Long findingId,
                                                            String action, String fixCommitSha) {
        Finding finding = requireProjectFinding(projectId, userId, findingId);
        FindingLifecycle target = FindingLifecycle.fromName(
                action == null ? null : action.trim().toUpperCase(Locale.ROOT));
        if (target == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "未知的处理动作");
        }
        FindingLifecycle current = finding.getLifecycle();
        if (current.isTerminal()) {
            throw new BusinessException(ErrorCode.FINDING_TRANSITION_ILLEGAL, "终态问题不可再流转");
        }
        requireActionRole(projectId, userId, finding, current, target);
        if (!FindingLifecycle.canTransition(current, target)) {
            throw new BusinessException(ErrorCode.FINDING_TRANSITION_ILLEGAL,
                    "不允许 " + current + " → " + target);
        }
        finding.moveLifecycle(target, userId, fixCommitSha);
        return toResponse(finding);
    }

    @Transactional
    public AgentFindingDtos.AgentFindingResponse assign(Long projectId, Long userId, Long findingId, Long assigneeId) {
        projectAuthorization.requireRole(projectId, userId, Set.of(ProjectRole.LEADER));
        Finding finding = requireProjectFinding(projectId, userId, findingId);
        if (finding.getLifecycle().isTerminal()) {
            throw new BusinessException(ErrorCode.FINDING_TRANSITION_ILLEGAL, "终态问题不可再指派");
        }
        if (projectAuthorization.roleOf(projectId, assigneeId).isEmpty()) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND, "指派对象必须是项目成员");
        }
        finding.assignTo(assigneeId);
        return toResponse(finding);
    }

    // ------------------------------------------------------------------ helpers

    /** 确认/驳回/验证/关闭 = REVIEWER/LEADER;开始修复/已修复 = 被指派人或 LEADER。 */
    private void requireActionRole(Long projectId, Long userId, Finding finding,
                                   FindingLifecycle current, FindingLifecycle target) {
        boolean fixSide = (current == FindingLifecycle.CONFIRMED && target == FindingLifecycle.IN_PROGRESS)
                || (current == FindingLifecycle.IN_PROGRESS && target == FindingLifecycle.FIXED);
        if (fixSide) {
            boolean assignee = userId.equals(finding.getAssigneeId());
            if (!assignee) {
                projectAuthorization.requireRole(projectId, userId, Set.of(ProjectRole.LEADER));
            }
            return;
        }
        projectAuthorization.requireRole(projectId, userId, REVIEW_ROLES);
    }

    private Finding requireProjectFinding(Long projectId, Long userId, Long findingId) {
        projectAuthorization.requireRead(projectId, userId);
        Finding finding = findings.findById(findingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FINDING_NOT_FOUND));
        AgentRun run = runs.findById(finding.getAgentRunId())
                .orElseThrow(() -> new BusinessException(ErrorCode.FINDING_NOT_FOUND));
        if (!projectId.equals(run.getProjectId())
                || scmContexts.findByAgentRunId(run.getId()).isEmpty()) {
            throw new BusinessException(ErrorCode.FINDING_NOT_FOUND);
        }
        return finding;
    }

    private AgentFindingDtos.AgentFindingResponse toResponse(Finding finding) {
        List<FindingEvidenceEntity> evidence = evidences.findByFindingIdOrderByIdAsc(finding.getId());
        FindingDecisionEntity decision = decisions.findByFindingIdOrderByIdAsc(finding.getId()).stream()
                .reduce((first, second) -> second).orElse(null);
        return AgentFindingDtos.AgentFindingResponse.from(finding, evidence, decision);
    }
}
