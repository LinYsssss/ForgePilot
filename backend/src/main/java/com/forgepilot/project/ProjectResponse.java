package com.forgepilot.project;

import java.time.Instant;
import java.util.Set;

public record ProjectResponse(long id, String name, ProjectStatus status, Instant createdAt,
        Set<ProjectRole> myRoles) {

    static ProjectResponse of(Project project, Set<ProjectRole> myRoles) {
        return new ProjectResponse(project.getId(), project.getName(), project.getStatus(),
                project.getCreatedAt(), myRoles);
    }
}
