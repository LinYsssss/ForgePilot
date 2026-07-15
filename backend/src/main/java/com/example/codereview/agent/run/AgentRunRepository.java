package com.example.codereview.agent.run;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {
    Optional<AgentRun> findByTriggerKey(String triggerKey);

    /**
     * All runs whose trigger key shares the given prefix — used to find sibling runs for the same
     * PR (provider + installation + PR number) across different head SHAs, so an older active run
     * can be superseded when a newer head arrives.
     */
    List<AgentRun> findByTriggerKeyStartingWith(String triggerKeyPrefix);
}
