package com.forgepilot.project;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /**
     * Serialises concurrent LEADER transfers on the project row. Without it two
     * transfers can both read one LEADER, both demote, and both promote; the
     * partial unique index would then let the later commit win silently.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :projectId")
    Optional<Project> findByIdForUpdate(long projectId);
}
