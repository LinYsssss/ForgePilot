package com.forgepilot.project;

import java.time.Instant;

public record ProjectResponse(long id, String name, ProjectStatus status, Instant createdAt,
        ProjectRole myRole) {

    static ProjectResponse of(Project project, ProjectRole myRole) {
        return new ProjectResponse(project.getId(), project.getName(), project.getStatus(),
                project.getCreatedAt(), myRole);
    }
}
