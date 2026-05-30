package com.example.codereview.repo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class RepositoryDtos {

    private RepositoryDtos() {
    }

    public record BindRepositoryRequest(
            @NotBlank @Size(max = 512) String repoUrl,
            @Size(max = 32) String provider,
            @Size(max = 128) String defaultBranch,
            @Size(max = 512) String accessToken
    ) {
    }

    public record RepositoryResponse(
            Long repositoryId,
            Long projectId,
            String repoUrl,
            String defaultBranch,
            String status
    ) {
        public static RepositoryResponse from(CodeRepositoryEntity entity) {
            return new RepositoryResponse(
                    entity.getId(),
                    entity.getProjectId(),
                    entity.getRepoUrl(),
                    entity.getDefaultBranch(),
                    entity.getStatus()
            );
        }
    }

    public record CommitResponse(
            String commitId,
            String parentCommitId,
            String authorName,
            String authorEmail,
            String message,
            Instant committedAt
    ) {
    }

    public record DiffFileResponse(
            String filePath,
            String changeType,
            int additions,
            int deletions,
            String diff
    ) {
    }

    public record CommitDiffResponse(
            String commitId,
            String baseCommitId,
            List<DiffFileResponse> files,
            String rawDiff
    ) {
    }
}
