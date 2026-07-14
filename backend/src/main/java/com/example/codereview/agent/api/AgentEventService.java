package com.example.codereview.agent.api;

import com.example.codereview.agent.api.AgentRunDtos.AgentRunDetail;
import com.example.codereview.agent.api.AgentRunDtos.AgentStepView;
import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentStateMachine;
import com.example.codereview.agent.run.AgentStep;
import com.example.codereview.agent.run.AgentStepRepository;
import com.example.codereview.common.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams Agent Run progress over Server-Sent Events. The database remains the source of truth: on
 * connect the client receives the current run snapshot and any persisted steps after its
 * {@code Last-Event-ID}, then live notifications. Event IDs are the step {@code sequenceNo}, so a
 * dropped client reconnects with {@code Last-Event-ID} and receives exactly the steps it missed.
 */
@Service
public class AgentEventService {

    private static final Logger log = LoggerFactory.getLogger(AgentEventService.class);
    private static final String RUN_EVENT = "agent-run";
    private static final String STEP_EVENT = "agent-step";

    private final AgentRunService agentRunService;
    private final AgentRunRepository runs;
    private final AgentStepRepository steps;
    private final AgentStateMachine stateMachine;
    private final ObjectMapper objectMapper;

    private final long emitterTimeoutMs;
    private final int maxSubscribersPerRun;
    private final int maxTotalSubscribers;

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final AtomicInteger totalSubscribers = new AtomicInteger();

    public AgentEventService(
            AgentRunService agentRunService,
            AgentRunRepository runs,
            AgentStepRepository steps,
            AgentStateMachine stateMachine,
            ObjectMapper objectMapper,
            @Value("${app.agent.events.emitter-timeout:30m}") Duration emitterTimeout,
            @Value("${app.agent.events.max-subscribers-per-run:20}") int maxSubscribersPerRun,
            @Value("${app.agent.events.max-total-subscribers:500}") int maxTotalSubscribers
    ) {
        this.agentRunService = agentRunService;
        this.runs = runs;
        this.steps = steps;
        this.stateMachine = stateMachine;
        this.objectMapper = objectMapper;
        this.emitterTimeoutMs = emitterTimeout.toMillis();
        this.maxSubscribersPerRun = maxSubscribersPerRun;
        this.maxTotalSubscribers = maxTotalSubscribers;
    }

    /**
     * Authorize the caller against the run's project, then return an emitter primed with the current
     * snapshot and the persisted step tail after {@code lastEventId}. Terminal runs complete the emitter
     * immediately; active runs stay registered for live notifications.
     */
    public SseEmitter subscribe(Long runId, Long userId, Long lastEventId) {
        AgentRunDetail detail = agentRunService.detail(runId, userId); // authorizes: 404/403
        if (totalSubscribers.get() >= maxTotalSubscribers) {
            throw new BusinessException(429, "Agent 事件订阅已达全局上限");
        }
        List<SseEmitter> existing = emitters.get(runId);
        if (!detail.terminal() && existing != null && existing.size() >= maxSubscribersPerRun) {
            throw new BusinessException(429, "该运行的事件订阅已达上限");
        }

        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);
        try {
            emitter.send(SseEmitter.event().name(RUN_EVENT).data(toJson(detail), MediaType.APPLICATION_JSON));
            for (AgentStep step : replayableSteps(runId, lastEventId)) {
                emitter.send(stepEvent(step));
            }
        } catch (IOException exception) {
            emitter.completeWithError(exception);
            return emitter;
        }

        if (detail.terminal()) {
            emitter.complete();
            return emitter;
        }
        register(runId, emitter);
        return emitter;
    }

    /**
     * Fan a persisted step change out to any live subscribers. Called after commit so subscribers only
     * ever observe durable state. When the run reaches a terminal state its emitters are completed.
     */
    public void publish(Long runId, int sequenceNo) {
        List<SseEmitter> targets = emitters.get(runId);
        if (targets == null || targets.isEmpty()) {
            return;
        }
        steps.findByAgentRunIdAndSequenceNo(runId, sequenceNo).ifPresent(step -> {
            SseEmitter.SseEventBuilder event = stepEvent(step);
            for (SseEmitter emitter : targets) {
                try {
                    emitter.send(event);
                } catch (IOException | IllegalStateException exception) {
                    remove(runId, emitter);
                    emitter.completeWithError(exception);
                }
            }
        });
        if (isTerminal(runId)) {
            completeAll(runId);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStepRecorded(AgentStepRecordedEvent event) {
        try {
            publish(event.agentRunId(), event.sequenceNo());
        } catch (RuntimeException exception) {
            log.warn("Failed to fan out Agent step {}#{} to subscribers", event.agentRunId(),
                    event.sequenceNo(), exception);
        }
    }

    int subscriberCount(Long runId) {
        List<SseEmitter> list = emitters.get(runId);
        return list == null ? 0 : list.size();
    }

    private List<AgentStep> replayableSteps(Long runId, Long lastEventId) {
        List<AgentStep> ordered = steps.findByAgentRunIdOrderBySequenceNo(runId);
        if (lastEventId == null) {
            return ordered;
        }
        return ordered.stream().filter(step -> step.getSequenceNo() > lastEventId).toList();
    }

    private SseEmitter.SseEventBuilder stepEvent(AgentStep step) {
        return SseEmitter.event()
                .id(String.valueOf(step.getSequenceNo()))
                .name(STEP_EVENT)
                .data(toJson(AgentStepView.from(step)), MediaType.APPLICATION_JSON);
    }

    private void register(Long runId, SseEmitter emitter) {
        emitters.computeIfAbsent(runId, key -> new CopyOnWriteArrayList<>()).add(emitter);
        totalSubscribers.incrementAndGet();
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onError(throwable -> remove(runId, emitter));
        emitter.onTimeout(emitter::complete);
    }

    private void remove(Long runId, SseEmitter emitter) {
        emitters.computeIfPresent(runId, (key, list) -> {
            if (list.remove(emitter)) {
                totalSubscribers.decrementAndGet();
            }
            return list.isEmpty() ? null : list;
        });
    }

    private void completeAll(Long runId) {
        List<SseEmitter> list = emitters.remove(runId);
        if (list == null) {
            return;
        }
        totalSubscribers.addAndGet(-list.size());
        for (SseEmitter emitter : list) {
            emitter.complete();
        }
    }

    private boolean isTerminal(Long runId) {
        return runs.findById(runId).map(AgentRun::getStatus).map(stateMachine::isTerminal).orElse(false);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Agent event payload", exception);
        }
    }
}
