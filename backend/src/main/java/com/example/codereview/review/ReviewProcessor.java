package com.example.codereview.review;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.ai.AiReviewClient;
import com.example.codereview.ai.AiReviewResult;
import com.example.codereview.ai.AiCallLogService;
import com.example.codereview.ai.AiMetrics;
import com.example.codereview.ai.TokenUsage;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.rag.RagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ReviewProcessor {

    /** Cap on the merged raw-response text persisted to the report (keeps the text column sane). */
    private static final int MAX_MERGED_RAW = 20000;

    private final ReviewTaskRepository tasks;
    private final RagService ragService;
    private final AiReviewClient aiReviewClient;
    private final AiCallLogService aiCallLogService;
    private final AiMetrics aiMetrics;
    private final ReviewTaskStatusService taskStatusService;
    private final ReviewResultWriter resultWriter;
    private final CoverageJudgeService coverageJudgeService;
    private final ObjectMapper objectMapper;
    private final int maxPromptChars;
    private final int maxDiffChars;
    private final int maxFiles;

    public ReviewProcessor(ReviewTaskRepository tasks, RagService ragService, AiReviewClient aiReviewClient,
                           AiCallLogService aiCallLogService, AiMetrics aiMetrics,
                           ReviewTaskStatusService taskStatusService, ReviewResultWriter resultWriter,
                           CoverageJudgeService coverageJudgeService, ObjectMapper objectMapper,
                           @Value("${app.review.max-prompt-chars:48000}") int maxPromptChars,
                           @Value("${app.review.max-diff-chars:20000}") int maxDiffChars,
                           @Value("${app.review.max-files:40}") int maxFiles) {
        this.tasks = tasks;
        this.ragService = ragService;
        this.aiReviewClient = aiReviewClient;
        this.aiCallLogService = aiCallLogService;
        this.aiMetrics = aiMetrics;
        this.taskStatusService = taskStatusService;
        this.resultWriter = resultWriter;
        this.coverageJudgeService = coverageJudgeService;
        this.objectMapper = objectMapper;
        this.maxPromptChars = maxPromptChars;
        this.maxDiffChars = maxDiffChars;
        this.maxFiles = maxFiles;
    }

    public void process(Long taskId) {
        ReviewTask task = tasks.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_TASK_NOT_FOUND, "审查任务不存在"));
        if (task.isTerminal()) {
            return;
        }
        try {
            taskStatusService.markRunning(taskId);
            // 五臂 flags(P4a):按下单时的组合执行;null = 生产默认全开,行为与引入前一致。
            ReviewFeatureFlags flags = ReviewFeatureFlags.parse(task.getFlagsJson(), objectMapper);
            String ragContext = flags.knowledge()
                    ? ragService.buildContext(task.getProjectId(), task.getDiffText(), task.getKnowledgeDocIds())
                    : "";
            // The project context is shared across chunks, so reserve one per-chunk diff budget for it.
            String reviewContext = capContext(ragContext, maxDiffChars);
            AiReviewResult result = reviewChunks(task, reviewContext);
            // 合并阶段单独一次 AC 覆盖判定(design §5):失败不阻塞报告,coverage 缺席即可。
            String coverageJson = flags.requirementContext()
                    ? coverageJudgeService.judgeQuietly(task, result.summary(), flags.evidenceVerification())
                    : null;
            resultWriter.saveSuccess(taskId, result, coverageJson);
        } catch (RuntimeException ex) {
            taskStatusService.markFailed(taskId, ex.getMessage());
            throw ex;
        }
    }

    public void markDead(Long taskId, String error) {
        taskStatusService.markDead(taskId, error);
    }

    /**
     * Splits the diff into per-file batches and reviews each as a separate AI call, then merges the
     * findings. A small diff that fits in one chunk takes exactly the old single-call path, so its
     * report (summary / risk / raw response) is unchanged.
     */
    private AiReviewResult reviewChunks(ReviewTask task, String context) {
        DiffSplitter.ChunkPlan plan = DiffSplitter.plan(task.getDiffText(), maxDiffChars, maxFiles);
        if (plan.chunks().size() == 1 && plan.skippedFiles() == 0) {
            return reviewWithLog(task, plan.chunks().get(0), context);
        }

        List<AiReviewResult.Issue> issues = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        StringBuilder mergedRaw = new StringBuilder();
        TokenUsage totalUsage = TokenUsage.none();
        String overallRisk = "NONE";
        int index = 0;
        for (String chunk : plan.chunks()) {
            index++;
            AiReviewResult result = reviewWithLog(task, chunk, context);
            issues.addAll(result.issues());
            totalUsage = totalUsage.plus(result.usage());
            overallRisk = higherRisk(overallRisk, result.overallRisk());
            if (result.summary() != null && !result.summary().isBlank()) {
                summaries.add(result.summary());
            }
            if (mergedRaw.length() < MAX_MERGED_RAW) {
                mergedRaw.append("# 分片 ").append(index).append('\n')
                        .append(result.rawResponse() == null ? "" : result.rawResponse())
                        .append("\n\n");
            }
        }
        String summary = aggregateSummary(plan, issues.size(), summaries);
        return new AiReviewResult(summary, overallRisk, issues, mergedRaw.toString(), totalUsage);
    }

    private AiReviewResult reviewWithLog(ReviewTask task, String diffText, String context) {
        int promptChars = chars(diffText) + chars(context);
        long start = System.nanoTime();
        try {
            AiReviewResult result = aiReviewClient.review(diffText, context);
            long latencyMs = elapsedMs(start);
            aiCallLogService.reviewSuccess(
                    task.getProjectId(),
                    task.getId(),
                    promptChars,
                    chars(result.rawResponse()),
                    result.usage(),
                    latencyMs
            );
            aiMetrics.recordSuccess(latencyMs, result.usage());
            return result;
        } catch (RuntimeException ex) {
            long latencyMs = elapsedMs(start);
            aiCallLogService.reviewFailed(
                    task.getProjectId(),
                    task.getId(),
                    promptChars,
                    latencyMs,
                    ex.getMessage()
            );
            aiMetrics.recordFailure(latencyMs);
            throw ex;
        }
    }

    private String aggregateSummary(DiffSplitter.ChunkPlan plan, int issueCount, List<String> chunkSummaries) {
        StringBuilder sb = new StringBuilder();
        sb.append("分片审查：共 ").append(plan.totalFiles()).append(" 个文件");
        if (plan.skippedFiles() > 0) {
            sb.append("（其中 ").append(plan.skippedFiles()).append(" 个因超过单任务文件上限 ").append(maxFiles).append(" 未审查）");
        }
        sb.append("，分 ").append(plan.chunks().size()).append(" 批调用模型，合计发现 ")
                .append(issueCount).append(" 个问题。");
        String detail = String.join(" ", chunkSummaries).trim();
        if (!detail.isEmpty()) {
            if (detail.length() > 1500) {
                detail = detail.substring(0, 1500) + "…";
            }
            sb.append('\n').append(detail);
        }
        return sb.toString();
    }

    private String capContext(String context, int diffReserveChars) {
        if (context == null || context.isBlank() || maxPromptChars <= 0) {
            return context;
        }
        int budget = maxPromptChars - Math.max(0, diffReserveChars);
        if (budget <= 0) {
            return "[项目上下文已省略：单片 Diff 已占满 prompt 预算 " + maxPromptChars + "]";
        }
        if (context.length() <= budget) {
            return context;
        }
        return context.substring(0, budget) + "\n\n[项目上下文已截断，超过 prompt 预算 " + maxPromptChars + "]";
    }

    private String higherRisk(String current, String candidate) {
        return riskRank(candidate) > riskRank(current) ? candidate : current;
    }

    private int riskRank(String risk) {
        if (risk == null) {
            return 0;
        }
        switch (risk.trim().toUpperCase(Locale.ROOT)) {
            case "HIGH":
                return 3;
            case "MEDIUM":
                return 2;
            case "LOW":
                return 1;
            default:
                return 0;
        }
    }

    private int chars(String value) {
        return value == null ? 0 : value.length();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
