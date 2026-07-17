package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.orchestration.steps.AnalyzingChangeStepExecutor;
import com.example.codereview.agent.orchestration.steps.ExecutingToolsStepExecutor;
import com.example.codereview.agent.orchestration.steps.GeneratingPatchStepExecutor;
import com.example.codereview.agent.orchestration.steps.PlanningStepExecutor;
import com.example.codereview.agent.orchestration.steps.PreparingRepositoryStepExecutor;
import com.example.codereview.agent.orchestration.steps.PublishingResultStepExecutor;
import com.example.codereview.agent.orchestration.steps.RetrievingContextStepExecutor;
import com.example.codereview.agent.orchestration.steps.ValidatingPatchStepExecutor;
import com.example.codereview.agent.orchestration.steps.VerifyingFindingsStepExecutor;
import com.example.codereview.agent.orchestration.steps.WaitingApprovalStepExecutor;
import com.example.codereview.agent.queue.AgentStepHandler;
import com.example.codereview.agent.run.AgentRunStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentStepExecutorRegistryTest {

    @Test
    void registersExactlyOneExecutorForEveryExecutableState() {
        AgentStepExecutorRegistry registry = new AgentStepExecutorRegistry(executors());

        assertThat(AgentStepExecutorRegistry.EXECUTABLE_STATES)
                .allSatisfy(state -> assertThat(registry.require(state).state()).isEqualTo(state));
        assertThatThrownBy(() -> registry.require(AgentRunStatus.RECEIVED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingDuplicateAndUnexpectedRegistrationsAtStartup() {
        List<AgentStepExecutor> missing = new ArrayList<>(executors());
        missing.remove(0);
        assertThatThrownBy(() -> new AgentStepExecutorRegistry(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");

        List<AgentStepExecutor> duplicate = new ArrayList<>(executors());
        duplicate.add(new PreparingRepositoryStepExecutor());
        assertThatThrownBy(() -> new AgentStepExecutorRegistry(duplicate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");

        AgentStepExecutor unexpected = mock(AgentStepExecutor.class);
        when(unexpected.state()).thenReturn(AgentRunStatus.RECEIVED);
        List<AgentStepExecutor> unexpectedList = new ArrayList<>(executors());
        unexpectedList.add(unexpected);
        assertThatThrownBy(() -> new AgentStepExecutorRegistry(unexpectedList))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected");
    }

    @Test
    void handlerDispatchesTypedContextAndRejectsWrongStateResult() {
        AgentStepExecutor executor = mock(AgentStepExecutor.class);
        when(executor.state()).thenReturn(AgentRunStatus.PREPARING_REPOSITORY);
        List<AgentStepExecutor> candidates = new ArrayList<>(executors());
        candidates.set(0, executor);
        AgentStepExecutorRegistry registry = new AgentStepExecutorRegistry(candidates);
        AgentStepHandler handler = new AgentStepHandler(registry);
        AgentStepExecutionContext context = context();
        AgentStepResult expected = AgentStepResult.checkpoint(AgentRunStatus.PREPARING_REPOSITORY);
        when(executor.execute(context)).thenReturn(expected);

        assertThat(handler.execute(context)).isEqualTo(expected);
        verify(executor).execute(context);

        when(executor.execute(context)).thenReturn(AgentStepResult.checkpoint(AgentRunStatus.PLANNING));
        assertThatThrownBy(() -> handler.execute(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid state");
    }

    private List<AgentStepExecutor> executors() {
        return List.of(
                new PreparingRepositoryStepExecutor(),
                new AnalyzingChangeStepExecutor(),
                new PlanningStepExecutor(),
                new ExecutingToolsStepExecutor(),
                new RetrievingContextStepExecutor(),
                new VerifyingFindingsStepExecutor(),
                new GeneratingPatchStepExecutor(),
                new ValidatingPatchStepExecutor(),
                new WaitingApprovalStepExecutor(),
                new PublishingResultStepExecutor()
        );
    }

    private AgentStepExecutionContext context() {
        return new AgentStepExecutionContext(
                1L, 2L, 3L, 4L, "head", AgentRunStatus.PREPARING_REPOSITORY,
                1, 0, "trace", false
        );
    }
}
