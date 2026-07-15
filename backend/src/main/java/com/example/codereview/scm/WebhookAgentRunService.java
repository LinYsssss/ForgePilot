package com.example.codereview.scm;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a verified, normalized SCM event into an Agent Run.
 *
 * <p>Introduced for the webhook receive path (Task 3); supersede semantics for stale head SHAs are
 * added in Task 5. A run is keyed by a deterministic {@link #triggerKey(NormalizedPullRequestEvent)}
 * (provider + installation + PR number + head SHA): the same event is idempotent (returns the
 * existing run), while a new head SHA yields a distinct key and a fresh run. The run is created in
 * {@code RECEIVED}; downstream scheduling drives it further, so the webhook can acknowledge quickly.
 */
@Service
public class WebhookAgentRunService {

    /** Outcome of {@link #startFromEvent}: the run plus whether it was newly created. */
    public record StartResult(AgentRun run, boolean created) {
    }

    private final AgentRunRepository agentRunRepository;

    public WebhookAgentRunService(AgentRunRepository agentRunRepository) {
        this.agentRunRepository = agentRunRepository;
    }

    @Transactional
    public StartResult startFromEvent(NormalizedPullRequestEvent event, ScmInstallation installation) {
        if (installation.getProjectId() == null || installation.getRepositoryId() == null) {
            throw new IllegalStateException("Installation is not bound to a project/repository");
        }
        String triggerKey = triggerKey(event);
        Optional<AgentRun> existing = agentRunRepository.findByTriggerKey(triggerKey);
        if (existing.isPresent()) {
            return new StartResult(existing.get(), false);
        }

        AgentRun run = new AgentRun(
                installation.getProjectId(),
                installation.getRepositoryId(),
                null,
                triggerKey,
                event.headSha());
        run = agentRunRepository.save(run);
        return new StartResult(run, true);
    }

    /** Deterministic, idempotent run key for a normalized event. */
    public static String triggerKey(NormalizedPullRequestEvent event) {
        return event.provider().name().toLowerCase()
                + ":" + event.installationRef()
                + ":pr:" + event.pullRequestNumber()
                + ":" + event.headSha();
    }
}
