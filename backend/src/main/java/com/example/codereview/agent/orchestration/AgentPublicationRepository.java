package com.example.codereview.agent.orchestration;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentPublicationRepository extends JpaRepository<AgentPublication, Long> {
    Optional<AgentPublication> findByIdempotencyKey(String idempotencyKey);
}
