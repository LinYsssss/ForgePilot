package com.example.codereview.review;

import com.example.codereview.ai.AiCallLogService;
import com.example.codereview.pullrequest.PullRequestRepository;
import com.example.codereview.requirement.AcceptanceCriterionRepository;
import com.example.codereview.requirement.RequirementEntity;
import com.example.codereview.requirement.RequirementLinkEntity;
import com.example.codereview.requirement.RequirementLinkRepository;
import com.example.codereview.requirement.RequirementRepository;
import com.example.codereview.review.CoverageDtos.AcCoverage;
import com.example.codereview.review.CoverageDtos.AcRef;
import com.example.codereview.review.CoverageDtos.CoverageInput;
import com.example.codereview.review.CoverageDtos.CoverageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AC 覆盖判定编排(P4a):需求解析(PR 链接优先、分支兜底)→ 合并阶段单独一次 LLM 调用 →
 * 证据引用校验(伪造引用丢弃,COVERED 降级 AT_RISK)→ 序列化。
 * 判定失败不阻塞报告落库——findings 主链路优先,coverage 缺席 + 日志。
 */
@Service
public class CoverageJudgeService {

    private static final Logger log = LoggerFactory.getLogger(CoverageJudgeService.class);

    private final RequirementLinkRepository links;
    private final RequirementRepository requirements;
    private final AcceptanceCriterionRepository criteria;
    private final PullRequestRepository pullRequests;
    private final CoverageJudgeClient judgeClient;
    private final AiCallLogService aiCallLogService;
    private final ObjectMapper objectMapper;
    private final int maxDiffExcerptChars;

    public CoverageJudgeService(RequirementLinkRepository links, RequirementRepository requirements,
                                AcceptanceCriterionRepository criteria, PullRequestRepository pullRequests,
                                CoverageJudgeClient judgeClient, AiCallLogService aiCallLogService,
                                ObjectMapper objectMapper,
                                @Value("${app.review.coverage-max-diff-chars:8000}") int maxDiffExcerptChars) {
        this.links = links;
        this.requirements = requirements;
        this.criteria = criteria;
        this.pullRequests = pullRequests;
        this.judgeClient = judgeClient;
        this.aiCallLogService = aiCallLogService;
        this.objectMapper = objectMapper;
        this.maxDiffExcerptChars = maxDiffExcerptChars;
    }

    /**
     * 为一次审查任务判定 AC 覆盖;无关联需求返回 null(纯质量审查,现状行为)。
     * 任何失败也返回 null——coverage 是增强信息,不许拖垮审查主链路。
     */
    public String judgeQuietly(ReviewTask task, String shardSummaries, boolean verifyEvidence) {
        String prRef = null;
        if (task.getPullRequestId() != null) {
            prRef = pullRequests.findById(task.getPullRequestId())
                    .map(pr -> "PR#" + pr.getPrNumber())
                    .orElse(null);
        }
        return judgeForRefs(task.getProjectId(), task.getId(), prRef, task.getBranchName(),
                task.getDiffText(), shardSummaries, verifyEvidence);
    }

    /**
     * 通用判定入口(P4b):交互式(ReviewTask)与 Agent 守门(webhook)两条链路共用,
     * 判定/解析/证据校验零分叉。prRef 形如 "PR#12";两个引用都解析不到需求 → null。
     */
    public String judgeForRefs(Long projectId, Long taskIdForLog, String prRef, String branchName,
                               String diffText, String shardSummaries, boolean verifyEvidence) {
        try {
            CoverageInput input = resolveByRefs(projectId, prRef, branchName).orElse(null);
            if (input == null || input.acs().isEmpty()) {
                return null;
            }
            String diffExcerpt = excerpt(diffText);
            long start = System.nanoTime();
            CoverageResult result;
            try {
                result = judgeClient.judge(input, shardSummaries, diffExcerpt);
            } catch (RuntimeException ex) {
                aiCallLogService.coverageJudgeFailed(projectId, taskIdForLog,
                        diffExcerpt.length(), elapsedMs(start), ex.getMessage());
                throw ex;
            }
            aiCallLogService.coverageJudgeSuccess(projectId, taskIdForLog,
                    diffExcerpt.length(),
                    result.rawResponse() == null ? 0 : result.rawResponse().length(),
                    result.totalTokens(), elapsedMs(start));
            List<AcCoverage> coverage = verifyEvidence
                    ? CoverageJudgeParser.verifyEvidence(result.coverage(), diffText)
                    : result.coverage();
            return objectMapper.writeValueAsString(new CoverageBlock(
                    input.requirementId(), input.requirementCode(), input.requirementTitle(), coverage));
        } catch (Exception ex) {
            log.warn("coverage judge failed: refTask={}, projectId={}", taskIdForLog, projectId, ex);
            return null;
        }
    }

    /** 落库/响应共用的 coverage 区块结构。 */
    public record CoverageBlock(Long requirementId, String requirementCode, String requirementTitle,
                                List<AcCoverage> coverage) {
    }

    /** PR 链接优先,分支兜底;命中多条取第一条并记日志。 */
    private Optional<CoverageInput> resolveByRefs(Long projectId, String prRef, String branchName) {
        Optional<RequirementEntity> requirement = Optional.empty();
        if (prRef != null && !prRef.isBlank()) {
            requirement = firstLinked(projectId, "PULL_REQUEST", prRef);
        }
        if (requirement.isEmpty() && branchName != null && !branchName.isBlank()) {
            requirement = firstLinked(projectId, "BRANCH", branchName);
        }
        return requirement.map(entity -> {
            List<AcRef> acs = criteria.findByRequirementIdOrderBySeqAsc(entity.getId()).stream()
                    .map(ac -> new AcRef("AC" + ac.getSeq(), ac.getText()))
                    .toList();
            return new CoverageInput(entity.getId(), "REQ-" + entity.getSeq(), entity.getTitle(),
                    entity.getDescription(), acs);
        });
    }

    private Optional<RequirementEntity> firstLinked(Long projectId, String type, String ref) {
        List<RequirementLinkEntity> matches = links.findByProjectIdAndLinkTypeAndRef(projectId, type, ref);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            log.info("multiple requirements linked to {} {} (project {}), judging the first",
                    type, ref, projectId);
        }
        return requirements.findById(matches.get(0).getRequirementId());
    }

    private String excerpt(String diffText) {
        if (diffText == null) {
            return "";
        }
        return diffText.length() <= maxDiffExcerptChars ? diffText : diffText.substring(0, maxDiffExcerptChars);
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
