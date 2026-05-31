package com.example.codereview.review;

import com.example.codereview.ai.AiReviewClient;
import com.example.codereview.ai.AiReviewResult;
import com.example.codereview.ai.AiCallLogService;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.model.ModelRiskClient;
import com.example.codereview.model.ModelRiskSignal;
import com.example.codereview.rag.RagService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ReviewProcessor {

    private final ReviewTaskRepository tasks;
    private final RagService ragService;
    private final AiReviewClient aiReviewClient;
    private final AiCallLogService aiCallLogService;
    private final ModelRiskClient modelRiskClient;
    private final ReviewTaskStatusService taskStatusService;
    private final ReviewResultWriter resultWriter;
    private final int maxPromptChars;

    public ReviewProcessor(ReviewTaskRepository tasks, RagService ragService, AiReviewClient aiReviewClient,
                           AiCallLogService aiCallLogService, ModelRiskClient modelRiskClient,
                           ReviewTaskStatusService taskStatusService, ReviewResultWriter resultWriter,
                           @Value("${app.review.max-prompt-chars:48000}") int maxPromptChars) {
        this.tasks = tasks;
        this.ragService = ragService;
        this.aiReviewClient = aiReviewClient;
        this.aiCallLogService = aiCallLogService;
        this.modelRiskClient = modelRiskClient;
        this.taskStatusService = taskStatusService;
        this.resultWriter = resultWriter;
        this.maxPromptChars = maxPromptChars;
    }

    public void process(Long taskId) {
        ReviewTask task = tasks.findById(taskId)
                .orElseThrow(() -> new BusinessException(6002, "审查任务不存在"));
        if (task.isTerminal()) {
            return;
        }
        try {
            taskStatusService.markRunning(taskId);
            ModelRiskSignal riskSignal = modelRiskClient.predict(task.getProjectId(), task.getId(), task.getDiffText()).orElse(null);
            String ragContext = ragService.buildContext(task.getProjectId(), task.getDiffText(), task.getKnowledgeDocIds());
            String reviewContext = capContext(buildReviewContext(ragContext, riskSignal), task.getDiffText());
            AiReviewResult result = reviewWithLog(task, reviewContext);
            resultWriter.saveSuccess(taskId, result);
        } catch (RuntimeException ex) {
            taskStatusService.markFailed(taskId, ex.getMessage());
            throw ex;
        }
    }

    public void markDead(Long taskId, String error) {
        taskStatusService.markDead(taskId, error);
    }

    private AiReviewResult reviewWithLog(ReviewTask task, String ragContext) {
        int promptChars = chars(task.getDiffText()) + chars(ragContext);
        long start = System.nanoTime();
        try {
            AiReviewResult result = aiReviewClient.review(task.getDiffText(), ragContext);
            aiCallLogService.reviewSuccess(
                    task.getProjectId(),
                    task.getId(),
                    promptChars,
                    chars(result.rawResponse()),
                    elapsedMs(start)
            );
            return result;
        } catch (RuntimeException ex) {
            aiCallLogService.reviewFailed(
                    task.getProjectId(),
                    task.getId(),
                    promptChars,
                    elapsedMs(start),
                    ex.getMessage()
            );
            throw ex;
        }
    }

    private String buildReviewContext(String ragContext, ModelRiskSignal riskSignal) {
        if (riskSignal == null) {
            return ragContext;
        }
        String modelContext = riskSignal.toPromptContext();
        if (ragContext == null || ragContext.isBlank()) {
            return modelContext;
        }
        return modelContext + "\n\nRAG 检索上下文：\n" + ragContext;
    }

    private String capContext(String context, String diffText) {
        if (context == null || context.isBlank() || maxPromptChars <= 0) {
            return context;
        }
        int budget = maxPromptChars - chars(diffText);
        if (budget <= 0) {
            return "[项目上下文已省略：Diff 已占满 prompt 预算 " + maxPromptChars + "]";
        }
        if (context.length() <= budget) {
            return context;
        }
        return context.substring(0, budget) + "\n\n[项目上下文已截断，超过 prompt 预算 " + maxPromptChars + "]";
    }

    private int chars(String value) {
        return value == null ? 0 : value.length();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
