package com.example.reposage.sandbox;

import java.time.Clock;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;

@SpringBootApplication
public class SandboxRunnerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SandboxRunnerApplication.class, args);
    }

    @Bean
    Clock sandboxClock() {
        return Clock.systemUTC();
    }

    @Bean
    SandboxJobSigner sandboxJobSigner() {
        return new SandboxJobSigner();
    }

    @Bean
    SandboxReplayGuard sandboxReplayGuard() {
        return new SandboxReplayGuard();
    }

    @Bean
    ContainerPolicy containerPolicy() {
        return new ContainerPolicy(Map.of(
                "sandbox.health", new ContainerPolicy.CommandSpec(List.of("/bin/true"), List.of())));
    }

    @Bean
    DockerClient dockerClient() {
        return new DockerCliClient();
    }

    @Bean
    RepositoryUrlPolicy repositoryUrlPolicy() {
        return new RepositoryUrlPolicy();
    }

    @Bean
    RepositoryArchiveExtractor repositoryArchiveExtractor(RepositoryUrlPolicy urls) {
        return new RepositoryArchiveExtractor(
                new RepositoryArchiveLimits(10_000, 16L * 1024 * 1024, 512L * 1024 * 1024), urls);
    }

    @Bean
    RepositoryReadCommandHandler repositoryReadCommandHandler() {
        return new RepositoryReadCommandHandler(65_536, 1_000, 65_536);
    }

    @Bean
    WorkspaceArchiveResolver workspaceArchiveResolver(
            @Value("${app.sandbox.archive-root:/app/archives}") String archiveRoot) throws java.io.IOException {
        return new WorkspaceArchiveResolver(Path.of(archiveRoot));
    }

    @Bean
    DependencyPreparationPolicy dependencyPreparationPolicy() {
        return new DependencyPreparationPolicy();
    }

    @Bean
    DependencyCacheManager dependencyCacheManager(
            DependencyPreparationPolicy policy,
            @Value("${app.sandbox.dependency-cache-root:/app/.cache/dependencies}") String cacheRoot) {
        return new DependencyCacheManager(Path.of(cacheRoot), policy);
    }
}
