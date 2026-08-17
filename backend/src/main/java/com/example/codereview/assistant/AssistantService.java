package com.example.codereview.assistant;

import com.example.codereview.ai.AiCallLogService;
import com.example.codereview.ai.TokenUsage;
import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.ProjectAuthorization;
import com.example.codereview.context.ContextBuilder;
import com.example.codereview.context.ContextScene;
import com.example.codereview.requirement.RequirementRepository;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private final boolean enabled;
    private final long emitterTimeoutMs;
    private final int maxHistoryChars;
    private final ProjectAuthorization authorization;
    private final RequirementRepository requirements;
    private final ContextBuilder contextBuilder;
    private final AssistantPromptAssembler promptAssembler;
    private final AssistantModelClient modelClient;
    private final AiCallLogService logs;
    private final ThreadPoolTaskExecutor executor;
    private final Semaphore permits;

    public AssistantService(
            ProjectAuthorization authorization,
            RequirementRepository requirements,
            ContextBuilder contextBuilder,
            AssistantPromptAssembler promptAssembler,
            AssistantModelClient modelClient,
            AiCallLogService logs,
            @Qualifier("assistantTaskExecutor") ThreadPoolTaskExecutor executor,
            @Value("${app.assistant.enabled:true}") boolean enabled,
            @Value("${app.assistant.emitter-timeout-ms:300000}") long emitterTimeoutMs,
            @Value("${app.assistant.history.max-total-chars:24000}") int maxHistoryChars,
            @Value("${app.assistant.max-concurrent:4}") int maxConcurrent) {
        this.authorization = authorization;
        this.requirements = requirements;
        this.contextBuilder = contextBuilder;
        this.promptAssembler = promptAssembler;
        this.modelClient = modelClient;
        this.logs = logs;
        this.executor = executor;
        this.enabled = enabled;
        this.emitterTimeoutMs = emitterTimeoutMs;
        this.maxHistoryChars = maxHistoryChars;
        this.permits = new Semaphore(Math.max(1, maxConcurrent));
    }

    public boolean enabled() {
        return enabled;
    }

    public SseEmitter stream(Long projectId, Long requirementId, Long userId, AssistantDtos.StreamRequest request) {
        requireEnabled();
        ValidatedRequest validated = validate(request);
        authorization.requireRead(projectId, userId);
        requirements.findByIdAndProjectId(requirementId, projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REQUIREMENT_NOT_FOUND));
        if (!permits.tryAcquire()) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }

        ContextBuilder.ContextBundle context;
        AssistantPrompt prompt;
        try {
            context = contextBuilder.build(ContextScene.ASSISTANT, projectId,
                    ContextBuilder.Refs.assistant(userId, requirementId));
            prompt = promptAssembler.assemble(context, validated.history(), validated.message());
        } catch (RuntimeException ex) {
            permits.release();
            throw ex;
        }

        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(error -> cancelled.set(true));
        try {
            executor.execute(() -> runStream(projectId, context, prompt, emitter, cancelled));
        } catch (RuntimeException ex) {
            permits.release();
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
        return emitter;
    }

    private void runStream(Long projectId, ContextBuilder.ContextBundle context, AssistantPrompt prompt,
                           SseEmitter emitter, AtomicBoolean cancelled) {
        long started = System.nanoTime();
        StringBuilder response = new StringBuilder();
        try {
            Set<String> usedSourceIds = Set.copyOf(prompt.sourceIds());
            List<String> truncatedSections = new java.util.ArrayList<>(context.truncatedSections());
            prompt.truncatedSections().forEach(section -> {
                if (!truncatedSections.contains(section)) {
                    truncatedSections.add(section);
                }
            });
            send(emitter, "context", new AssistantDtos.ContextPayload(
                    context.sources().stream()
                            .filter(source -> usedSourceIds.contains(source.id()))
                            .map(source -> new AssistantDtos.SourcePayload(
                                    source.id(), source.type(), source.title(), source.ref()))
                            .toList(),
                    List.copyOf(truncatedSections), context.warnings()));
            TokenUsage usage = modelClient.stream(prompt, delta -> {
                if (!cancelled.get() && delta != null && !delta.isEmpty()) {
                    response.append(delta);
                    send(emitter, "delta", new AssistantDtos.DeltaPayload(delta));
                }
            }, cancelled::get);
            if (!cancelled.get()) {
                TokenUsage safeUsage = usage == null ? TokenUsage.none() : usage;
                logSuccess(projectId, prompt.promptChars(), response.length(), safeUsage, elapsedMs(started));
                send(emitter, "done", new AssistantDtos.DonePayload(
                        safeUsage.promptTokens(), safeUsage.completionTokens(), safeUsage.totalTokens()));
                emitter.complete();
            } else {
                logFailure(projectId, prompt.promptChars(), elapsedMs(started), "CANCELLED");
            }
        } catch (RuntimeException ex) {
            logFailure(projectId, prompt.promptChars(), elapsedMs(started), ex.getClass().getSimpleName());
            if (!cancelled.get()) {
                try {
                    send(emitter, "error", new AssistantDtos.ErrorPayload(
                            ErrorCode.AI_CALL_FAILED.name(), ErrorCode.AI_CALL_FAILED.defaultMessage()));
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    emitter.completeWithError(ex);
                }
            }
        } finally {
            permits.release();
        }
    }

    private ValidatedRequest validate(AssistantDtos.StreamRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "问题不能为空");
        }
        String message = request.message().strip();
        List<AssistantDtos.HistoryMessage> history = request.history() == null ? List.of() : List.copyOf(request.history());
        int total = 0;
        for (AssistantDtos.HistoryMessage item : history) {
            if (item == null || item.content() == null || item.content().isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "历史消息不能为空");
            }
            String role = item.role() == null ? "" : item.role().strip().toUpperCase(Locale.ROOT);
            if (!"USER".equals(role) && !"ASSISTANT".equals(role)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "历史消息角色无效");
            }
            total += item.content().length();
            if (total > maxHistoryChars) {
                throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "历史消息总长度超出限制");
            }
        }
        return new ValidatedRequest(message, history.stream().map(item -> new AssistantDtos.HistoryMessage(
                item.role().strip().toUpperCase(Locale.ROOT), item.content().strip())).toList());
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "研发助手当前未启用");
        }
    }

    private void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            throw new IllegalStateException("assistant stream closed", ex);
        }
    }

    private void logSuccess(Long projectId, int promptChars, int responseChars, TokenUsage usage, long latencyMs) {
        try {
            logs.assistantSuccess(projectId, promptChars, responseChars, usage, latencyMs);
        } catch (RuntimeException ex) {
            log.warn("assistant success log write failed: {}", ex.getClass().getSimpleName());
        }
    }

    private void logFailure(Long projectId, int promptChars, long latencyMs, String failureClass) {
        try {
            logs.assistantFailed(projectId, promptChars, latencyMs, failureClass);
        } catch (RuntimeException ex) {
            log.warn("assistant failure log write failed: {}", ex.getClass().getSimpleName());
        }
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private record ValidatedRequest(String message, List<AssistantDtos.HistoryMessage> history) {
    }
}
