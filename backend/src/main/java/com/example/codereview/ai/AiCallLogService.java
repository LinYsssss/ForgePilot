package com.example.codereview.ai;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.ai.AiCallLogDtos.AiCallLogResponse;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.project.ProjectService;
import com.example.codereview.review.ReviewTask;
import com.example.codereview.review.ReviewTaskRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiCallLogService {

    public static final String REVIEW = "CHAT_REVIEW";
    public static final String EMBEDDING_INDEX = "EMBEDDING_INDEX";
    public static final String EMBEDDING_SEARCH = "EMBEDDING_SEARCH";
    public static final String REQUIREMENT_CHECK = "REQUIREMENT_CHECK";
    public static final String COVERAGE_JUDGE = "COVERAGE_JUDGE";

    private final AiCallLogRepository logs;
    private final ProjectService projectService;
    private final ReviewTaskRepository tasks;
    private final String provider;
    private final String chatModel;
    private final String embeddingProvider;
    private final String embeddingModel;

    public AiCallLogService(
            AiCallLogRepository logs,
            ProjectService projectService,
            ReviewTaskRepository tasks,
            @Value("${app.ai.provider}") String provider,
            @Value("${app.ai.chat-model}") String chatModel,
            @Value("${app.ai.embedding-provider}") String embeddingProvider,
            @Value("${app.ai.embedding-model}") String embeddingModel
    ) {
        this.logs = logs;
        this.projectService = projectService;
        this.tasks = tasks;
        this.provider = provider;
        this.chatModel = chatModel;
        this.embeddingProvider = embeddingProvider;
        this.embeddingModel = embeddingModel;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reviewSuccess(Long projectId, Long taskId, int promptChars, int responseChars, TokenUsage usage, long latencyMs) {
        TokenUsage u = usage == null ? TokenUsage.none() : usage;
        save(projectId, taskId, REVIEW, chatModel, promptChars, responseChars,
                u.promptTokens(), u.completionTokens(), u.totalTokens(), latencyMs, "SUCCESS", null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reviewFailed(Long projectId, Long taskId, int promptChars, long latencyMs, String errorMessage) {
        save(projectId, taskId, REVIEW, chatModel, promptChars, 0, 0, 0, 0, latencyMs, "FAILED", errorMessage);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void embeddingSuccess(Long projectId, String requestType, int promptChars, int dimensions, long latencyMs) {
        save(projectId, null, requestType, embeddingProvider, embeddingModel, promptChars, dimensions, 0, 0, 0, latencyMs, "SUCCESS", null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void embeddingFailed(Long projectId, String requestType, int promptChars, long latencyMs, String errorMessage) {
        save(projectId, null, requestType, embeddingProvider, embeddingModel, promptChars, 0, 0, 0, 0, latencyMs, "FAILED", errorMessage);
    }

    // 体检调用挂 project 不挂 task(需求体检与审查任务无关联);token 只有 total(上游 usage 口径)。
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requirementCheckSuccess(Long projectId, int promptChars, int responseChars, int totalTokens, long latencyMs) {
        save(projectId, null, REQUIREMENT_CHECK, chatModel, promptChars, responseChars, 0, 0, totalTokens, latencyMs, "SUCCESS", null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requirementCheckFailed(Long projectId, int promptChars, long latencyMs, String errorMessage) {
        save(projectId, null, REQUIREMENT_CHECK, chatModel, promptChars, 0, 0, 0, 0, latencyMs, "FAILED", errorMessage);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void coverageJudgeSuccess(Long projectId, Long taskId, int promptChars, int responseChars,
                                     int totalTokens, long latencyMs) {
        save(projectId, taskId, COVERAGE_JUDGE, chatModel, promptChars, responseChars, 0, 0, totalTokens, latencyMs, "SUCCESS", null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void coverageJudgeFailed(Long projectId, Long taskId, int promptChars, long latencyMs, String errorMessage) {
        save(projectId, taskId, COVERAGE_JUDGE, chatModel, promptChars, 0, 0, 0, 0, latencyMs, "FAILED", errorMessage);
    }

    @Transactional(readOnly = true)
    public List<AiCallLogResponse> list(Long userId, Long projectId, Long taskId, Integer limit) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 200));
        PageRequest page = PageRequest.of(0, safeLimit);
        if (taskId != null) {
            ReviewTask task = tasks.findById(taskId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_TASK_NOT_FOUND, "审查任务不存在"));
            projectService.getRequired(task.getProjectId(), userId);
            if (projectId != null && !projectId.equals(task.getProjectId())) {
                throw new BusinessException(400, "任务不属于指定项目");
            }
            return (projectId == null
                    ? logs.findByTaskIdOrderByCreatedAtDesc(taskId, page)
                    : logs.findByProjectIdAndTaskIdOrderByCreatedAtDesc(projectId, taskId, page))
                    .stream()
                    .map(AiCallLogResponse::from)
                    .toList();
        }
        if (projectId == null) {
            throw new BusinessException(400, "projectId 或 taskId 至少传一个");
        }
        projectService.getRequired(projectId, userId);
        return logs.findByProjectIdOrderByCreatedAtDesc(projectId, page)
                .stream()
                .map(AiCallLogResponse::from)
                .toList();
    }

    /** Paginated listing; AI call logs grow with every review/retrieval for the project's lifetime. */
    @Transactional(readOnly = true)
    public com.example.codereview.common.api.PageResponse<AiCallLogResponse> listPage(
            Long userId, Long projectId, Long taskId, Integer page, Integer size) {
        PageRequest pageRequest = PageRequest.of(
                com.example.codereview.common.api.PageResponse.sanitizePage(page),
                com.example.codereview.common.api.PageResponse.sanitizeSize(size));
        if (taskId != null) {
            ReviewTask task = tasks.findById(taskId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_TASK_NOT_FOUND, "审查任务不存在"));
            projectService.getRequired(task.getProjectId(), userId);
            if (projectId != null && !projectId.equals(task.getProjectId())) {
                throw new BusinessException(400, "任务不属于指定项目");
            }
            var rows = projectId == null
                    ? logs.findPageByTaskIdOrderByCreatedAtDesc(taskId, pageRequest)
                    : logs.findPageByProjectIdAndTaskIdOrderByCreatedAtDesc(projectId, taskId, pageRequest);
            return com.example.codereview.common.api.PageResponse.from(rows, AiCallLogResponse::from);
        }
        if (projectId == null) {
            throw new BusinessException(400, "projectId 或 taskId 至少传一个");
        }
        projectService.getRequired(projectId, userId);
        return com.example.codereview.common.api.PageResponse.from(
                logs.findPageByProjectIdOrderByCreatedAtDesc(projectId, pageRequest), AiCallLogResponse::from);
    }

    private void save(Long projectId, Long taskId, String requestType, String model, int promptChars,
                      int responseChars, int promptTokens, int completionTokens, int totalTokens,
                      long latencyMs, String status, String errorMessage) {
        save(projectId, taskId, requestType, provider, model, promptChars, responseChars,
                promptTokens, completionTokens, totalTokens, latencyMs, status, errorMessage);
    }

    private void save(Long projectId, Long taskId, String requestType, String providerName, String model, int promptChars,
                      int responseChars, int promptTokens, int completionTokens, int totalTokens,
                      long latencyMs, String status, String errorMessage) {
        logs.save(new AiCallLog(
                projectId,
                taskId,
                requestType,
                providerName == null || providerName.isBlank() ? "unknown" : providerName,
                model == null || model.isBlank() ? "unknown" : model,
                Math.max(0, promptChars),
                Math.max(0, responseChars),
                Math.max(0, promptTokens),
                Math.max(0, completionTokens),
                Math.max(0, totalTokens),
                Math.max(0, latencyMs),
                status,
                truncate(errorMessage)
        ));
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 2000) {
            return message;
        }
        return message.substring(0, 2000);
    }
}
