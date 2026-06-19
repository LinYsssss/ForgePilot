package com.example.codereview.agent.run;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {
    Optional<AgentRun> findByTriggerKey(String triggerKey);
}
