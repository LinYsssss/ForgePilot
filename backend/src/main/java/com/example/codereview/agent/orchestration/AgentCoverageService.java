package com.example.codereview.agent.orchestration;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.git.GitCliService;
import com.example.codereview.repo.CodeRepositoryEntity;
import com.example.codereview.repo.CodeRepositoryJpaRepository;
import com.example.codereview.repo.RepositoryDtos.CommitDiffResponse;
import com.example.codereview.review.CoverageDtos.AcCoverage;
import com.example.codereview.review.CoverageJudgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agent 守门链路的 AC 覆盖接入(P4b):发布结果前,按 run 的 SCM 上下文(PR#号 + base/head)
 * 反查关联需求并复用 {@link CoverageJudgeService} 判定,结论落 {@code agent_run.coverage_json}
 * (P5 门禁扩展的输入),同时产出 SCM 回写用的人读摘要行。
 *
 * <p>整条路径 best-effort:diff 取不到、无关联需求、判定失败——一律静默返回空行集,
 * 发布主链路零影响(coverage 是增强信息,不是守门前置条件;门禁语义 P5 才扩展)。
 */
@Service
public class AgentCoverageService {

    private static final Logger log = LoggerFactory.getLogger(AgentCoverageService.class);

    private final AgentRunRepository runs;
    private final AgentScmContextRepository scmContexts;
    private final CodeRepositoryJpaRepository repositories;
    private final GitCliService gitCliService;
    private final CoverageJudgeService coverageJudgeService;
    private final ObjectMapper objectMapper;

    public AgentCoverageService(AgentRunRepository runs, AgentScmContextRepository scmContexts,
                                CodeRepositoryJpaRepository repositories, GitCliService gitCliService,
                                CoverageJudgeService coverageJudgeService, ObjectMapper objectMapper) {
        this.runs = runs;
        this.scmContexts = scmContexts;
        this.repositories = repositories;
        this.gitCliService = gitCliService;
        this.coverageJudgeService = coverageJudgeService;
        this.objectMapper = objectMapper;
    }

    /** 判定并落 run;返回 SCM 回写摘要行(无 coverage 时为空列表)。永不抛出。 */
    public List<String> judgeAndAttach(Long runId, String headSha) {
        try {
            AgentRun run = runs.findById(runId).orElse(null);
            AgentScmContext context = scmContexts.findByAgentRunId(runId).orElse(null);
            if (run == null || context == null) {
                return List.of();
            }
            CodeRepositoryEntity repository = run.getRepositoryId() == null ? null
                    : repositories.findById(run.getRepositoryId()).orElse(null);
            if (repository == null) {
                return List.of();
            }
            CommitDiffResponse diff = gitCliService.diff(repository, headSha, context.getBaseSha());
            String coverageJson = coverageJudgeService.judgeForRefs(
                    run.getProjectId(), null,
                    "PR#" + context.getPullRequestNumber(), null,
                    diff.rawDiff(), null, true);
            if (coverageJson == null) {
                return List.of();
            }
            run.attachCoverage(coverageJson);
            runs.save(run);
            return summaryLines(coverageJson);
        } catch (RuntimeException ex) {
            log.warn("agent coverage judge failed: runId={}", runId, ex);
            return List.of();
        }
    }

    private List<String> summaryLines(String coverageJson) {
        try {
            CoverageJudgeService.CoverageBlock block =
                    objectMapper.readValue(coverageJson, CoverageJudgeService.CoverageBlock.class);
            List<String> lines = new ArrayList<>();
            lines.add("需求一致性 " + block.requirementCode() + " " + block.requirementTitle() + ":");
            for (AcCoverage ac : block.coverage()) {
                lines.add(ac.acId() + " [" + verdictLabel(ac.verdict()) + "] " + ac.acText());
            }
            return List.copyOf(lines);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String verdictLabel(String verdict) {
        return switch (verdict) {
            case "COVERED" -> "已覆盖";
            case "NOT_FOUND" -> "未发现";
            case "AT_RISK" -> "存在风险";
            default -> verdict;
        };
    }
}
