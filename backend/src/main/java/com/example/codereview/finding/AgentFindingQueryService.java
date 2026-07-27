package com.example.codereview.finding;

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
        projectService.getRequired(projectId, userId);
        AgentRun run = runs.findById(agentRunId)
                .orElseThrow(() -> new BusinessException(404, "Agent Run 不存在"));
        if (!projectId.equals(run.getProjectId())) {
            throw new BusinessException(404, "Agent Run 不属于该项目");
        }

        List<Finding> rows = findings.findByAgentRunIdOrderByIdAsc(agentRunId);
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
