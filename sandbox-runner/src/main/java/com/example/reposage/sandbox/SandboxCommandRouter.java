package com.example.reposage.sandbox;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public final class SandboxCommandRouter implements SandboxExecutor {

    private final RepositoryCommandExecutor repository;
    private final DockerSandboxExecutor docker;

    public SandboxCommandRouter(RepositoryCommandExecutor repository, DockerSandboxExecutor docker) {
        this.repository = repository;
        this.docker = docker;
    }

    @Override
    public SandboxResult execute(SandboxJob job) {
        if (job.commandId().equals("repo.unpack")
                || job.commandId().equals("git.file")
                || job.commandId().equals("git.diff")
                || job.commandId().equals("code.search")) {
            return repository.execute(job);
        }
        return docker.execute(job);
    }
}
