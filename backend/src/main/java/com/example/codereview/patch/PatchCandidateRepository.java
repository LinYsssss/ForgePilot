package com.example.codereview.patch;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatchCandidateRepository extends JpaRepository<PatchCandidate, Long> {
    List<PatchCandidate> findByAgentRunIdOrderByIdAsc(Long agentRunId);
}
