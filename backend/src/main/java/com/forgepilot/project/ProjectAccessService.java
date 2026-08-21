package com.forgepilot.project;

import java.util.Set;

import com.forgepilot.common.ApiException;
import org.springframework.stereotype.Service;

/**
 * The single authorization entry point for everything inside a project.
 * {@code requirement} and any later feature go through here rather than reading
 * {@link ProjectMemberRepository} themselves.
 *
 * <p>A caller who is not a member gets the same answer as for a project that does
 * not exist, so ids cannot be probed across projects. A caller who <em>is</em> a
 * member but lacks the role gets 403: they already know the project exists, so
 * that answer leaks nothing further.
 */
@Service
public class ProjectAccessService {

    private final ProjectMemberRepository members;

    ProjectAccessService(ProjectMemberRepository members) {
        this.members = members;
    }

    public ProjectMember requireMember(long projectId, long userId) {
        return members.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(ApiException::notFound);
    }

    public ProjectMember requireRole(long projectId, long userId, ProjectRole... allowed) {
        ProjectMember member = requireMember(projectId, userId);
        if (!Set.of(allowed).contains(member.getRole())) {
            throw ApiException.forbidden();
        }
        return member;
    }
}
