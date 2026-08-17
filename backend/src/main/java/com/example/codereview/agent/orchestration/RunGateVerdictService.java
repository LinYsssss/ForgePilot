package com.example.codereview.agent.orchestration;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.GateVerdict;
import com.example.codereview.finding.Finding;
import com.example.codereview.finding.FindingDecisionEntity;
import com.example.codereview.finding.FindingDecisionRepository;
import com.example.codereview.finding.FindingLifecycle;
import com.example.codereview.finding.FindingRepository;
import com.example.codereview.finding.FindingSeverity;
import com.example.codereview.review.CoverageJudgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Calculates and persists the P5 PASS/WARN/BLOCK run gate before publication. */
@Service
public class RunGateVerdictService {

    private final AgentRunRepository runs;
    private final FindingRepository findings;
    private final FindingDecisionRepository decisions;
    private final ObjectMapper objectMapper;

    public RunGateVerdictService(AgentRunRepository runs, FindingRepository findings,
                                 FindingDecisionRepository decisions, ObjectMapper objectMapper) {
        this.runs = runs;
        this.findings = findings;
        this.decisions = decisions;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public GateEvaluation evaluateAndAttach(Long runId) {
        AgentRun run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Agent run is missing"));
        List<Finding> allFindings = findings.findByAgentRunIdOrderByIdAsc(runId);
        List<Finding> verified = allFindings.stream()
                .filter(finding -> "verified".equals(finding.getStatus()))
                .toList();
        Map<Long, FindingDecisionEntity> latestDecisions = latestDecisions(verified);
        List<Finding> blocking = verified.stream()
                .filter(this::isActive)
                .filter(finding -> {
                    FindingDecisionEntity decision = latestDecisions.get(finding.getId());
                    return decision != null && Boolean.TRUE.equals(decision.getBlocking());
                })
                .toList();

        GateVerdict verdict;
        if (!blocking.isEmpty()) {
            verdict = GateVerdict.BLOCK;
        } else if (coverageWarns(run.getCoverageJson()) || verified.stream().anyMatch(this::warns)) {
            verdict = GateVerdict.WARN;
        } else {
            verdict = GateVerdict.PASS;
        }
        run.attachGateVerdict(verdict);
        runs.save(run);
        return new GateEvaluation(verdict, blocking);
    }

    private Map<Long, FindingDecisionEntity> latestDecisions(List<Finding> verified) {
        List<Long> ids = verified.stream().map(Finding::getId).filter(java.util.Objects::nonNull).toList();
        Map<Long, FindingDecisionEntity> latest = new HashMap<>();
        if (!ids.isEmpty()) {
            decisions.findByFindingIdInOrderByIdAsc(ids)
                    .forEach(decision -> latest.put(decision.getFindingId(), decision));
        }
        return latest;
    }

    private boolean warns(Finding finding) {
        if (finding.getLifecycle() == FindingLifecycle.FIXED) {
            return true;
        }
        return isActive(finding)
                && (finding.getSeverity() == FindingSeverity.HIGH
                || finding.getSeverity() == FindingSeverity.CRITICAL);
    }

    private boolean isActive(Finding finding) {
        return !finding.getLifecycle().isTerminal();
    }

    private boolean coverageWarns(String coverageJson) {
        if (coverageJson == null || coverageJson.isBlank()) {
            return false;
        }
        try {
            CoverageJudgeService.CoverageBlock block = objectMapper.readValue(
                    coverageJson, CoverageJudgeService.CoverageBlock.class);
            return block.coverage() != null && block.coverage().stream()
                    .anyMatch(ac -> "NOT_FOUND".equals(ac.verdict()) || "AT_RISK".equals(ac.verdict()));
        } catch (Exception ignored) {
            return false;
        }
    }

    public record GateEvaluation(GateVerdict verdict, List<Finding> blockingFindings) {
        public GateEvaluation {
            blockingFindings = blockingFindings == null ? List.of() : List.copyOf(blockingFindings);
        }
    }
}
