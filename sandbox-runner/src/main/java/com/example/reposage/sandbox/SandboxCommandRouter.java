package com.example.reposage.sandbox;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public final class SandboxCommandRouter implements SandboxExecutor {

    private final RepositoryCommandExecutor repository;
    private final DockerSandboxExecutor docker;
    private final PatchValidationExecutor patches;

    public SandboxCommandRouter(RepositoryCommandExecutor repository, DockerSandboxExecutor docker,
                                PatchValidationExecutor patches) {
        this.repository = repository;
        this.docker = docker;
        this.patches = patches;
    }

    @Override
    public SandboxResult execute(SandboxJob job) {
        if (job.commandId().equals("repo.unpack")
                || job.commandId().equals("git.file")
                || job.commandId().equals("git.diff")
                || job.commandId().equals("code.search")) {
            return repository.execute(job);
        }
        if (job.commandId().equals("patch.validate")) {
            return patches.execute(job);
        }
        return docker.execute(job);
    }
}
