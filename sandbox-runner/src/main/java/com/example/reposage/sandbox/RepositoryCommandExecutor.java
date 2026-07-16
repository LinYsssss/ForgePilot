package com.example.reposage.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Executes fixed repository read commands after safe archive extraction. */
@Component
public final class RepositoryCommandExecutor implements SandboxExecutor {

    private final WorkspaceArchiveResolver archives;
    private final RepositoryArchiveExtractor extractor;
    private final RepositoryReadCommandHandler commands;
    private final Path workRoot;

    @Autowired
    public RepositoryCommandExecutor(
            WorkspaceArchiveResolver archives,
            RepositoryArchiveExtractor extractor,
            RepositoryReadCommandHandler commands,
            @Value("${app.sandbox.work-root:/app/.work}") String workRoot) {
        this(archives, extractor, commands, Path.of(workRoot));
    }

    RepositoryCommandExecutor(
            WorkspaceArchiveResolver archives,
            RepositoryArchiveExtractor extractor,
            RepositoryReadCommandHandler commands,
            Path workRoot) {
        this.archives = archives;
        this.extractor = extractor;
        this.commands = commands;
        this.workRoot = workRoot.toAbsolutePath().normalize();
    }

    @Override
    public SandboxResult execute(SandboxJob job) {
        Path workspace = null;
        try {
            Path archive = archives.resolve(job.workspaceArchiveRef());
            Files.createDirectories(workRoot);
            workspace = Files.createTempDirectory(workRoot, "repo-");
            RepositoryArchiveSummary summary = extractor.extract(archive, workspace);
            RepositoryCommandResult result = switch (job.commandId()) {
                case "repo.unpack" -> RepositoryCommandResult.success(
                        "files=" + summary.fileCount() + ",bytes=" + summary.totalBytes(), false);
                case "git.file" -> job.args().size() > 1
                        ? commands.file(workspace, requiredArg(job, 0), optionalInt(job, 1, 1))
                        : commands.file(workspace, requiredArg(job, 0));
                case "git.diff" -> job.args().size() > 2
                        ? commands.diff(workspace, optionalInt(job, 2, 1))
                        : commands.diff(workspace);
                case "code.search" -> job.args().size() > 2
                        ? commands.search(workspace, requiredArg(job, 0), optionalInt(job, 1, 1), optionalInt(job, 2, 1))
                        : commands.search(workspace, requiredArg(job, 0));
                default -> throw new SecurityException("repository commandId is not allowlisted");
            };
            return new SandboxResult(
                    job.jobId(), result.status(), result.status() == SandboxJobStatus.SUCCEEDED ? 0 : null,
                    result.output(), result.truncated(), result.message());
        } catch (SecurityException ex) {
            return new SandboxResult(job.jobId(), SandboxJobStatus.REJECTED, null, "", false, ex.getMessage());
        } catch (IOException ex) {
            return new SandboxResult(
                    job.jobId(), SandboxJobStatus.ENVIRONMENT_INCOMPLETE, null, "", false,
                    "repository archive is unavailable: " + ex.getMessage());
        } catch (RuntimeException ex) {
            return new SandboxResult(job.jobId(), SandboxJobStatus.FAILED, null, "", false, ex.getMessage());
        } finally {
            deleteWorkspace(workspace);
        }
    }

    private static String requiredArg(SandboxJob job, int index) {
        if (job.args().size() <= index || job.args().get(index).isBlank()) {
            throw new IllegalArgumentException("repository command argument is missing");
        }
        return job.args().get(index);
    }

    private static int optionalInt(SandboxJob job, int index, int fallback) {
        if (job.args().size() <= index) {
            return fallback;
        }
        try {
            return Integer.parseInt(job.args().get(index));
        } catch (NumberFormatException ex) {
            throw new SecurityException("repository command numeric argument is invalid");
        }
    }

    private static void deleteWorkspace(Path workspace) {
        if (workspace == null || !Files.exists(workspace)) {
            return;
        }
        try (var paths = Files.walk(workspace)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
