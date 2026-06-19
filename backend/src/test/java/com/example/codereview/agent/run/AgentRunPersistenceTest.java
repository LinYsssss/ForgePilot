package com.example.codereview.agent.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("dev")
class AgentRunPersistenceTest {

    @Autowired
    private AgentRunRepository runs;

    @Autowired
    private AgentStepRepository steps;

    @Test
    void savesRunAndOrderedStepWithOptimisticVersioning() {
        AgentRun run = runs.saveAndFlush(new AgentRun(
                11L,
                22L,
                33L,
                "github:delivery-123",
                "abc123"
        ));

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.RECEIVED);
        assertThat(run.getVersion()).isZero();

        AgentStep step = steps.saveAndFlush(AgentStep.pending(
                run.getId(),
                1,
                AgentRunStatus.PREPARING_REPOSITORY
        ));

        AgentRun reloaded = runs.findById(run.getId()).orElseThrow();
        List<AgentStep> timeline = steps.findByAgentRunIdOrderBySequenceNo(run.getId());

        assertThat(reloaded.getTriggerKey()).isEqualTo("github:delivery-123");
        assertThat(reloaded.getHeadSha()).isEqualTo("abc123");
        assertThat(timeline).extracting(AgentStep::getId).containsExactly(step.getId());
        assertThat(timeline.get(0).getStatus()).isEqualTo(AgentStepStatus.PENDING);

        reloaded.advanceTo(AgentRunStatus.PREPARING_REPOSITORY, 1);
        runs.saveAndFlush(reloaded);

        assertThat(runs.findById(run.getId()).orElseThrow().getVersion()).isEqualTo(1L);
    }
}
