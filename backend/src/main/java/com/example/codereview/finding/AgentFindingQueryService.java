package com.example.codereview.finding;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.finding.AgentFindingDtos.AgentFindingResponse;
import com.example.codereview.project.ProjectService;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 查询某次 Agent Run 产出的 Finding。证据链与门禁裁决此前只落库、没有出口,
 * 前端因此只能显示占位数据;这里把三者装配成对外视图。
 */
@Service
public class AgentFindingQueryService {

    private final FindingRepository findings;
    private final FindingEvidenceRepository evidences;
    private final FindingDecisionRepository decisions;
    private final AgentRunRepository runs;
    private final ProjectService projectService;

    public AgentFindingQueryService(FindingRepository findings,
                                    FindingEvidenceRepository evidences,
                                    FindingDecisionRepository decisions,
                                    AgentRunRepository runs,
                                    ProjectService projectService) {
        this.findings = findings;
        this.evidences = evidences;
        this.decisions = decisions;
        this.runs = runs;
        this.projectService = projectService;
    }

    public List<AgentFindingResponse> list(Long projectId, Long agentRunId, Long userId) {
        requireOwnedRun(projectId, agentRunId, userId);
        return assemble(findings.findByAgentRunIdOrderByIdAsc(agentRunId));
    }

    /** Paginated listing; findings of a run are bounded but can still be dozens per page. */
    public com.example.codereview.common.api.PageResponse<AgentFindingResponse> list(
            Long projectId, Long agentRunId, Long userId, Integer page, Integer size) {
        requireOwnedRun(projectId, agentRunId, userId);
        var pageRequest = org.springframework.data.domain.PageRequest.of(
                com.example.codereview.common.api.PageResponse.sanitizePage(page),
                com.example.codereview.common.api.PageResponse.sanitizeSize(size));
        var rowsPage = findings.findByAgentRunIdOrderByIdAsc(agentRunId, pageRequest);
        List<AgentFindingResponse> items = assemble(rowsPage.getContent());
        return new com.example.codereview.common.api.PageResponse<>(
                items, rowsPage.getNumber(), rowsPage.getSize(), rowsPage.getTotalElements(), rowsPage.getTotalPages());
    }

    private void requireOwnedRun(Long projectId, Long agentRunId, Long userId) {
        projectService.getRequired(projectId, userId);
        AgentRun run = runs.findById(agentRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent Run 不存在"));
        if (!projectId.equals(run.getProjectId())) {
            throw new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent Run 不属于该项目");
        }
    }

    /** 装配证据链与最新裁决;入参行序即出参序。 */
    private List<AgentFindingResponse> assemble(List<Finding> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(Finding::getId).toList();

        Map<Long, List<FindingEvidenceEntity>> evidenceByFinding = evidences.findByFindingIdInOrderByIdAsc(ids)
                .stream()
                .collect(Collectors.groupingBy(FindingEvidenceEntity::getFindingId));
        // 同一 Finding 可能多次裁决(重跑/权重版本变化),取最后一条为当前结论。
        Map<Long, FindingDecisionEntity> latestDecision = decisions.findByFindingIdInOrderByIdAsc(ids)
                .stream()
                .collect(Collectors.toMap(FindingDecisionEntity::getFindingId, d -> d, (first, second) -> second));

        return rows.stream()
                .map(finding -> AgentFindingResponse.from(
                        finding,
                        evidenceByFinding.getOrDefault(finding.getId(), Collections.emptyList()),
                        latestDecision.get(finding.getId())))
                .toList();
    }
}
