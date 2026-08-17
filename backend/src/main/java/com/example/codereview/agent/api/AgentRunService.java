package com.example.codereview.agent.api;

import com.example.codereview.common.api.ErrorCode;
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
import com.example.codereview.common.security.ProjectAuthorization;
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
    private final ProjectAuthorization projectAuthorization;
    private final ApplicationEventPublisher events;

    public AgentRunService(
            AgentRunRepository runs,
            AgentStepRepository steps,
            AgentStateMachine stateMachine,
            AgentRunTransitionService transitions,
            AgentStepPublisher publisher,
            ProjectService projects,
            ProjectAuthorization projectAuthorization,
            ApplicationEventPublisher events
    ) {
        this.runs = runs;
        this.steps = steps;
        this.stateMachine = stateMachine;
        this.transitions = transitions;
        this.publisher = publisher;
        this.projects = projects;
        this.projectAuthorization = projectAuthorization;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public AgentRunDetail detail(Long runId, Long userId) {
        return toDetail(requireOwnedRun(runId, userId));
    }

    @Transactional(readOnly = true)
    public List<AgentRunDetail> listForProject(Long projectId, Long userId) {
        projects.getRequired(projectId, userId);
        return runs.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(run -> toDetail(run)).toList();
    }

    /** Paginated listing; agent runs accumulate for the lifetime of a project. */
    @Transactional(readOnly = true)
    public com.example.codereview.common.api.PageResponse<AgentRunDetail> listForProject(
            Long projectId, Long userId, Integer page, Integer size) {
        projects.getRequired(projectId, userId);
        var pageRequest = org.springframework.data.domain.PageRequest.of(
                com.example.codereview.common.api.PageResponse.sanitizePage(page),
                com.example.codereview.common.api.PageResponse.sanitizeSize(size));
        return com.example.codereview.common.api.PageResponse.from(
                runs.findByProjectIdOrderByCreatedAtDesc(projectId, pageRequest), this::toDetail);
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
        // 运维类动作(P1a 矩阵):仅 LEADER;成员仍可读 detail/timeline。
        projectAuthorization.requireWrite(run.getProjectId(), userId);
        if (stateMachine.isTerminal(run.getStatus())) {
            if (run.getStatus() == AgentRunStatus.CANCELED) {
                return toDetail(run);
            }
            throw new BusinessException(ErrorCode.AGENT_RUN_CONFLICT, "运行已结束，无法取消");
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
        projectAuthorization.requireWrite(run.getProjectId(), userId);
        if (run.getStatus() != AgentRunStatus.FAILED && run.getStatus() != AgentRunStatus.TIMED_OUT) {
            throw new BusinessException(ErrorCode.AGENT_RUN_CONFLICT, "只有失败或超时的运行可以重试");
        }
        int stepSequence = run.getCurrentStepSequence();
        AgentStep step = Optional.of(stepSequence)
                .filter(sequence -> sequence > 0)
                .flatMap(sequence -> steps.findForUpdate(run.getId(), sequence))
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_CONFLICT, "没有可重试的步骤"));
        if (step.isSucceeded()) {
            throw new BusinessException(ErrorCode.AGENT_RUN_CONFLICT, "该步骤已成功，无需重试");
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

    /**
     * run 级对象授权守卫的唯一实现:先 404 掉不存在的 run(不泄露存在性),再由项目守卫
     * 按 owner 判 403/404。所有「路径只带 runId」的端点必须走这里而不是各自复刻——
     * 守卫逻辑一旦分叉,矩阵测试罩不住的新规则会静默漏到副本之外(反馈闭环等跨包调用方复用)。
     */
    public AgentRun requireOwnedRun(Long runId, Long userId) {
        AgentRun run = runs.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent 运行不存在"));
        projects.getRequired(run.getProjectId(), userId);
        return run;
    }

    private AgentRunDetail toDetail(AgentRun run) {
        return AgentRunDetail.from(run, stateMachine.isTerminal(run.getStatus()));
    }
}
