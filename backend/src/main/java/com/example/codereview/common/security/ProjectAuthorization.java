package com.example.codereview.common.security;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.project.ProjectEntity;
import com.example.codereview.project.ProjectRepository;
import org.springframework.stereotype.Component;

/**
 * Single place where "does this project belong to this user" is decided.
 *
 * <p>Frozen in Phase 0 (see {@code docs/并行实施拆分方案.md}). Track A applies it across the business
 * controllers while closing the object-level authorization gaps; Track B owns the file. The method
 * surface is deliberately tiny so it does not need to change once both tracks are underway — call
 * sites that also need the entity should keep using {@code ProjectService.getRequired}.
 *
 * <p>Semantics match the existing convention: a missing project is a 404 and a foreign project is a
 * 403, so enumeration cannot distinguish "not yours" from "does not exist" beyond what the owner
 * already knows.
 *
 * <p>There is intentionally no administrator bypass. Adding one would widen access for every
 * existing endpoint at once; if it is ever needed it belongs here, behind an explicit role check
 * and its own negative tests.
 */
@Component
public class ProjectAuthorization {

    private final ProjectRepository projects;

    public ProjectAuthorization(ProjectRepository projects) {
        this.projects = projects;
    }

    /** Throws unless {@code userId} may read the project. */
    public void requireRead(Long projectId, Long userId) {
        requireOwned(projectId, userId);
    }

    /**
     * Throws unless {@code userId} may modify the project. Currently identical to
     * {@link #requireRead}; kept separate so a future read-only collaborator role does not require
     * revisiting every call site.
     */
    public void requireWrite(Long projectId, Long userId) {
        requireOwned(projectId, userId);
    }

    private void requireOwned(Long projectId, Long userId) {
        if (projectId == null || userId == null) {
            throw new BusinessException(ErrorCode.PROJECT_FORBIDDEN);
        }
        ProjectEntity project =
                projects.findById(projectId).orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_NOT_FOUND));
        if (!userId.equals(project.getOwnerId())) {
            throw new BusinessException(ErrorCode.PROJECT_FORBIDDEN);
        }
    }
}
