package com.example.codereview.agent.api;

import com.example.codereview.agent.api.AgentRunDtos.AgentRunDetail;
import com.example.codereview.agent.api.AgentRunDtos.AgentRunTimeline;
import com.example.codereview.agent.api.AgentRunDtos.AgentStepView;
import com.example.codereview.agent.outbox.AgentRunTransitionService;
import com.example.codereview.agent.queue.AgentStepPublisher;
import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.run.AgentStateMachine;
import com.example.codereview.agent.run.AgentStep;
import com.example.codereview.agent.run.AgentStepRepository;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.project.ProjectService;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Query and control-plane operations for Agent Runs, exposed by {@link AgentRunController}. All reads
 * and mutations authorize the caller against the run's owning project, and the persisted database row
 * is always the authoritative state returned to clients.
 */
@Service
public class AgentRunService {

    private final AgentRunRepository runs;
    private final AgentStepRepository steps;
    private final AgentStateMachine stateMachine;
    private final AgentRunTransitionService transitions;
    private final AgentStepPublisher publisher;
    private final ProjectService projects;
    private final ApplicationEventPublisher events;

    public AgentRunService(
            AgentRunRepository runs,
            AgentStepRepository steps,
            AgentStateMachine stateMachine,
            AgentRunTransitionService transitions,
            AgentStepPublisher publisher,
            ProjectService projects,
            ApplicationEventPublisher events
    ) {
        this.runs = runs;
        this.steps = steps;
        this.stateMachine = stateMachine;
        this.transitions = transitions;
        this.publisher = publisher;
        this.projects = projects;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public AgentRunDetail detail(Long runId, Long userId) {
        return toDetail(requireOwnedRun(runId, userId));
    }

    @Transactional(readOnly = true)
    public AgentRunTimeline timeline(Long runId, Long userId) {
        AgentRun run = requireOwnedRun(runId, userId);
        List<AgentStepView> stepViews = steps.findByAgentRunIdOrderBySequenceNo(run.getId())
                .stream()
                .map(AgentStepView::from)
                .toList();
        return new AgentRunTimeline(toDetail(run), stepViews);
    }

    @Transactional
    public AgentRunDetail cancel(Long runId, Long userId) {
        AgentRun run = requireOwnedRun(runId, userId);
        if (stateMachine.isTerminal(run.getStatus())) {
            if (run.getStatus() == AgentRunStatus.CANCELED) {
                return toDetail(run);
            }
            throw new BusinessException(409, "运行已结束，无法取消");
        }
        int stepSequence = run.getCurrentStepSequence();
        cancelActiveStep(run.getId(), stepSequence);
        run.requestCancellation();
        transitions.transitionAndEnqueue(
                run.getId(),
                AgentRunStatus.CANCELED,
                stepSequence,
                "cancel:run:" + run.getId(),
                "AGENT_CANCEL",
                "{\"agentRunId\":" + run.getId() + ",\"type\":\"CANCEL\"}",
                "cancel:" + run.getId()
        );
        if (stepSequence > 0) {
            events.publishEvent(new AgentStepRecordedEvent(run.getId(), stepSequence));
        }
        return toDetail(run);
    }

    @Transactional
    public AgentRunDetail retry(Long runId, Long userId) {
        AgentRun run = requireOwnedRun(runId, userId);
        if (run.getStatus() != AgentRunStatus.FAILED && run.getStatus() != AgentRunStatus.TIMED_OUT) {
            throw new BusinessException(409, "只有失败或超时的运行可以重试");
        }
        int stepSequence = run.getCurrentStepSequence();
        AgentStep step = Optional.of(stepSequence)
                .filter(sequence -> sequence > 0)
                .flatMap(sequence -> steps.findForUpdate(run.getId(), sequence))
                .orElseThrow(() -> new BusinessException(409, "没有可重试的步骤"));
        if (step.isSucceeded()) {
            throw new BusinessException(409, "该步骤已成功，无需重试");
        }
        run.reopenForRetry();
        publisher.republishForRetry(step);
        return toDetail(run);
    }

    private void cancelActiveStep(Long runId, int stepSequence) {
        if (stepSequence <= 0) {
            return;
        }
        steps.findForUpdate(runId, stepSequence)
                .filter(step -> !step.isTerminal())
                .ifPresent(AgentStep::cancel);
    }

    private AgentRun requireOwnedRun(Long runId, Long userId) {
        AgentRun run = runs.findById(runId)
                .orElseThrow(() -> new BusinessException(404, "Agent 运行不存在"));
        projects.getRequired(run.getProjectId(), userId);
        return run;
    }

    private AgentRunDetail toDetail(AgentRun run) {
        return AgentRunDetail.from(run, stateMachine.isTerminal(run.getStatus()));
    }
}
