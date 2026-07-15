package com.example.codereview.scm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.run.AgentStateMachine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies the SCM-event → Agent Run rules: one verified event creates exactly one RECEIVED run, an
 * exact replay (same provider+installation+PR+head SHA) returns the original run and creates nothing
 * new, and a newer head SHA starts a fresh run while superseding the older active run for the same PR.
 */
@DataJpaTest
@Import({WebhookAgentRunService.class, AgentStateMachine.class})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:webhookrun;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class WebhookAgentRunServiceTest {

    @Autowired
    private WebhookAgentRunService service;

    @Autowired
    private AgentRunRepository agentRuns;

    @Test
    void oneEventCreatesOneReceivedRun() {
        WebhookAgentRunService.StartResult result = service.startFromEvent(event("headA"), installation());

        assertThat(result.created()).isTrue();
        assertThat(result.run().getStatus()).isEqualTo(AgentRunStatus.RECEIVED);
        assertThat(agentRuns.count()).isEqualTo(1);
    }

    @Test
    void duplicateEventReturnsOriginalRunAndCreatesNothing() {
        WebhookAgentRunService.StartResult first = service.startFromEvent(event("headA"), installation());
        // Same head SHA => same trigger key, so a replayed delivery is a no-op.
        WebhookAgentRunService.StartResult second = service.startFromEvent(event("headA"), installation());

        assertThat(second.created()).isFalse();
        assertThat(second.run().getId()).isEqualTo(first.run().getId());
        assertThat(agentRuns.count()).isEqualTo(1);
    }

    @Test
    void newerHeadShaCreatesNewRunAndSupersedesOlder() {
        WebhookAgentRunService.StartResult older = service.startFromEvent(event("headA"), installation());
        WebhookAgentRunService.StartResult newer = service.startFromEvent(event("headB"), installation());

        assertThat(newer.created()).isTrue();
        assertThat(agentRuns.count()).isEqualTo(2);

        AgentRun reloadedOlder = agentRuns.findById(older.run().getId()).orElseThrow();
        assertThat(reloadedOlder.getStatus()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(reloadedOlder.isCancellationRequested()).isTrue();

        AgentRun reloadedNewer = agentRuns.findById(newer.run().getId()).orElseThrow();
        assertThat(reloadedNewer.getStatus()).isEqualTo(AgentRunStatus.RECEIVED);
    }

    private static NormalizedPullRequestEvent event(String headSha) {
        return new NormalizedPullRequestEvent(
                ScmProviderType.GITHUB, "inst-1", "acme/widgets", "https://host/acme/widgets.git",
                7, "Add widget", "octocat", "feature/widget", "main",
                "base000", headSha, "opened", "delivery-" + headSha);
    }

    private static ScmInstallation installation() {
        ScmInstallation installation = new ScmInstallation();
        installation.setProvider(ScmProviderType.GITHUB);
        installation.setExternalInstallationId("inst-1");
        installation.setProjectId(10L);
        installation.setRepositoryId(20L);
        return installation;
    }
}
