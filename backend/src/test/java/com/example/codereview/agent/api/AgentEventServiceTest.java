package com.example.codereview.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.api.AgentRunDtos.AgentRunDetail;
import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.run.AgentStateMachine;
import com.example.codereview.agent.run.AgentStep;
import com.example.codereview.agent.run.AgentStepRepository;
import com.example.codereview.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AgentEventServiceTest {

    private static final Long USER = 7L;

    private AgentRunService agentRunService;
    private AgentRunRepository runs;
    private AgentStepRepository steps;

    @BeforeEach
    void setUp() {
        agentRunService = Mockito.mock(AgentRunService.class);
        runs = Mockito.mock(AgentRunRepository.class);
        steps = Mockito.mock(AgentStepRepository.class);
        when(steps.findByAgentRunIdOrderBySequenceNo(any())).thenReturn(List.of());
    }

    private AgentEventService service(int maxPerRun, int maxTotal) {
        return new AgentEventService(
                agentRunService, runs, steps, new AgentStateMachine(),
                new ObjectMapper().findAndRegisterModules(),
                Duration.ofSeconds(30), maxPerRun, maxTotal
        );
    }

    private AgentRunDetail detail(long runId, AgentRunStatus status, boolean terminal) {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        return new AgentRunDetail(runId, 10L, 20L, null, "sha", status, 2, false, terminal, now, now);
    }

    @Test
    void registersActiveRunAndEnforcesPerRunSubscriberBound() {
        AgentEventService service = service(1, 10);
        when(agentRunService.detail(eq(1L), eq(USER)))
                .thenReturn(detail(1L, AgentRunStatus.EXECUTING_TOOLS, false));

        service.subscribe(1L, USER, null);
        assertThat(service.subscriberCount(1L)).isEqualTo(1);

        assertThatThrownBy(() -> service.subscribe(1L, USER, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上限");
        assertThat(service.subscriberCount(1L)).isEqualTo(1);
    }

    @Test
    void enforcesGlobalSubscriberBoundAcrossRuns() {
        AgentEventService service = service(5, 1);
        when(agentRunService.detail(eq(1L), eq(USER)))
                .thenReturn(detail(1L, AgentRunStatus.PLANNING, false));
        when(agentRunService.detail(eq(2L), eq(USER)))
                .thenReturn(detail(2L, AgentRunStatus.PLANNING, false));

        service.subscribe(1L, USER, null);
        assertThatThrownBy(() -> service.subscribe(2L, USER, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void completesTerminalRunWithoutRegistering() {
        AgentEventService service = service(5, 10);
        when(agentRunService.detail(eq(1L), eq(USER)))
                .thenReturn(detail(1L, AgentRunStatus.COMPLETED, true));

        service.subscribe(1L, USER, null);

        assertThat(service.subscriberCount(1L)).isZero();
    }

    @Test
    void publishCompletesAndRemovesEmittersWhenRunReachesTerminalState() {
        AgentEventService service = service(5, 10);
        when(agentRunService.detail(eq(1L), eq(USER)))
                .thenReturn(detail(1L, AgentRunStatus.EXECUTING_TOOLS, false));
        service.subscribe(1L, USER, null);
        assertThat(service.subscriberCount(1L)).isEqualTo(1);

        AgentStep step = AgentStep.pending(1L, 2, AgentRunStatus.EXECUTING_TOOLS);
        step.start(0);
        step.succeed("done");
        when(steps.findByAgentRunIdAndSequenceNo(1L, 2)).thenReturn(Optional.of(step));
        AgentRun terminalRun = new AgentRun(10L, 20L, null, "trigger", "sha");
        terminalRun.advanceTo(AgentRunStatus.COMPLETED, 2);
        when(runs.findById(1L)).thenReturn(Optional.of(terminalRun));

        service.publish(1L, 2);

        assertThat(service.subscriberCount(1L)).isZero();
    }

    @Test
    void publishKeepsEmitterRegisteredWhileRunIsActive() {
        AgentEventService service = service(5, 10);
        when(agentRunService.detail(eq(1L), eq(USER)))
                .thenReturn(detail(1L, AgentRunStatus.EXECUTING_TOOLS, false));
        service.subscribe(1L, USER, null);

        AgentStep step = AgentStep.pending(1L, 2, AgentRunStatus.EXECUTING_TOOLS);
        step.start(0);
        when(steps.findByAgentRunIdAndSequenceNo(1L, 2)).thenReturn(Optional.of(step));
        AgentRun activeRun = new AgentRun(10L, 20L, null, "trigger", "sha");
        activeRun.advanceTo(AgentRunStatus.EXECUTING_TOOLS, 2);
        when(runs.findById(1L)).thenReturn(Optional.of(activeRun));

        service.publish(1L, 2);

        assertThat(service.subscriberCount(1L)).isEqualTo(1);
    }
}
