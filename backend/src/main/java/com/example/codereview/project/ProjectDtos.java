package com.example.codereview.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class ProjectDtos {

    private ProjectDtos() {
    }

    public record CreateProjectRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 1000) String description,
            @Size(max = 128) String defaultBranch
    ) {
    }

    public record UpdateProjectRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 1000) String description,
            @Size(max = 128) String defaultBranch
    ) {
    }

    public record ProjectResponse(
            Long projectId,
            String name,
            String description,
            String defaultBranch,
            String status,
            Instant createdAt
    ) {
        public static ProjectResponse from(ProjectEntity entity) {
            return new ProjectResponse(
                    entity.getId(),
                    entity.getName(),
                    entity.getDescription(),
                    entity.getDefaultBranch(),
                    entity.getStatus(),
                    entity.getCreatedAt()
            );
        }
    }
}
