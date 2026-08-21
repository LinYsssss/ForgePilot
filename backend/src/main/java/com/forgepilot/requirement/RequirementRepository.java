package com.forgepilot.requirement;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Every read carries {@code projectId}; there is no bare-id lookup to bolt a
 * project check onto afterwards (ARCHITECTURE.md 2.3).
 *
 * <p>The graph pulls in the current revision because every caller needs its
 * title and seq: the {@code requirement} row itself has no prose (D011).
 */
public interface RequirementRepository extends JpaRepository<Requirement, Long> {

    @EntityGraph(attributePaths = "currentRevision")
    Optional<Requirement> findByProjectIdAndId(long projectId, long id);

    @EntityGraph(attributePaths = "currentRevision")
    List<Requirement> findByProjectIdOrderByIdAsc(long projectId);
}
