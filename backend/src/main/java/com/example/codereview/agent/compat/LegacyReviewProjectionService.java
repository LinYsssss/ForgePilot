package com.example.codereview.agent.compat;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.report.ReviewReport;
import com.example.codereview.report.ReviewReportRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects a completed Agent Run into the legacy {@code review_report} table so the existing review
 * UI keeps working while the Agent pipeline runs alongside it.
 *
 * <p>Projection is idempotent: {@code review_report.agent_run_id} is a unique key, so a repeated
 * projection of the same run returns the existing report instead of creating a duplicate. Per-issue
 * findings are not populated yet — the Agent control plane does not produce normalized findings until
 * the language plugins and evidence verification land in a later phase — so a projected report carries
 * a zero issue count and a neutral risk until then.
 */
@Service
public class LegacyReviewProjectionService {

    private static final String NO_FINDINGS_RISK = "NONE";

    private final AgentRunRepository runs;
    private final ReviewReportRepository reports;

    public LegacyReviewProjectionService(AgentRunRepository runs, ReviewReportRepository reports) {
        this.runs = runs;
        this.reports = reports;
    }

    @Transactional
    public ReviewReport project(Long agentRunId) {
        ReviewReport existing = reports.findByAgentRunId(agentRunId).orElse(null);
        if (existing != null) {
            return existing;
        }

        AgentRun run = runs.findById(agentRunId)
                .orElseThrow(() -> new BusinessException(404, "Agent 运行不存在"));
        if (run.getStatus() != AgentRunStatus.COMPLETED) {
            throw new BusinessException(409, "仅已完成的 Agent 运行可投影为审查报告");
        }

        ReviewReport report = ReviewReport.forAgentRun(
                agentRunId,
                run.getProjectId(),
                run.getHeadSha(),
                NO_FINDINGS_RISK,
                0,
                buildSummary(run),
                null
        );
        try {
            return reports.saveAndFlush(report);
        } catch (DataIntegrityViolationException concurrentProjection) {
            // Another projection won the unique agent_run_id key first; return the durable row.
            return reports.findByAgentRunId(agentRunId).orElseThrow(() -> concurrentProjection);
        }
    }

    private String buildSummary(AgentRun run) {
        return "Agent 审查完成：run #" + run.getId() + "（headSha " + run.getHeadSha() + "），暂无发现。";
    }
}
