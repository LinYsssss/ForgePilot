package com.example.codereview.common.security;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.member.ProjectMemberEntity;
import com.example.codereview.member.ProjectMemberRepository;
import com.example.codereview.member.ProjectRole;
import com.example.codereview.project.ProjectEntity;
import com.example.codereview.project.ProjectRepository;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Single place where "may this user act on this project" is decided.
 *
 * <p>Frozen in Phase 0 (see {@code docs/archive/并行实施拆分方案.md}); evolved in P1a
 * (ForgePilot upgrade) by the contract's own rule — keep the signatures, widen the semantics,
 * add new methods. {@link #requireRead} now accepts any project member instead of only the
 * owner; {@link #requireWrite} keeps its "highest-privilege action" meaning and maps to
 * LEADER; {@link #requireRole} is the fine-grained check for everything in between.
 *
 * <p>The owner is always treated as LEADER even if the membership row is missing — the row is
 * backfilled by V29 and written on project creation, but authorization must not depend on that
 * invariant holding forever.
 *
 * <p>Semantics match the existing convention: a missing project is a 404 and a foreign project is a
 * 403, so enumeration cannot distinguish "not yours" from "does not exist" beyond what a member
 * already knows.
 *
 * <p>There is intentionally no administrator bypass. Adding one would widen access for every
 * existing endpoint at once; if it is ever needed it belongs here, behind an explicit role check
 * and its own negative tests.
 */
@Component
public class ProjectAuthorization {

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;

    public ProjectAuthorization(ProjectRepository projects, ProjectMemberRepository members) {
        this.projects = projects;
        this.members = members;
    }

    /** Throws unless {@code userId} may read the project (any member). */
    public void requireRead(Long projectId, Long userId) {
        resolveRole(projectId, userId);
    }

    /** Throws unless {@code userId} may perform the highest-privilege actions (LEADER only). */
    public void requireWrite(Long projectId, Long userId) {
        requireRole(projectId, userId, Set.of(ProjectRole.LEADER));
    }

    /** Throws unless {@code userId}'s project role is one of {@code allowed}. */
    public void requireRole(Long projectId, Long userId, Set<ProjectRole> allowed) {
        ProjectRole role = resolveRole(projectId, userId);
        if (!allowed.contains(role)) {
            throw new BusinessException(ErrorCode.PROJECT_FORBIDDEN);
        }
    }

    /** The user's role in the project, empty when the project is missing or the user is no member. */
    public Optional<ProjectRole> roleOf(Long projectId, Long userId) {
        if (projectId == null || userId == null) {
            return Optional.empty();
        }
        return projects.findById(projectId).flatMap(project -> {
            if (userId.equals(project.getOwnerId())) {
                return Optional.of(ProjectRole.LEADER);
            }
            return members.findByProjectIdAndUserId(projectId, userId).map(ProjectMemberEntity::getRole);
        });
    }

    /** 404 for a missing project, 403 for a non-member; otherwise the member's role. */
    private ProjectRole resolveRole(Long projectId, Long userId) {
        if (projectId == null || userId == null) {
            throw new BusinessException(ErrorCode.PROJECT_FORBIDDEN);
        }
        ProjectEntity project =
                projects.findById(projectId).orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_NOT_FOUND));
        if (userId.equals(project.getOwnerId())) {
            return ProjectRole.LEADER;
        }
        return members.findByProjectIdAndUserId(projectId, userId)
                .map(ProjectMemberEntity::getRole)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_FORBIDDEN));
    }
}
