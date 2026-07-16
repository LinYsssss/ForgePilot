package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryCommandExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void routesSignedRepositoryJobThroughArchiveExtractorAndReadHandler() throws Exception {
        Path archiveRoot = Files.createDirectory(tempDir.resolve("archives"));
        Path archive = archiveRoot.resolve("repo.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("src/App.java"));
            output.write("class App {}".getBytes());
            output.closeEntry();
        }
        RepositoryCommandExecutor executor = new RepositoryCommandExecutor(
                new WorkspaceArchiveResolver(archiveRoot),
                new RepositoryArchiveExtractor(
                        new RepositoryArchiveLimits(100, 1024 * 1024, 2 * 1024 * 1024), new RepositoryUrlPolicy()),
                new RepositoryReadCommandHandler(1024, 10, 1024),
                tempDir.resolve("work"));

        SandboxResult result = executor.execute(new SandboxJob(
                "repo-job", "repo.zip", "ignored@sha256:abc", "git.file",
                List.of("src/App.java"), new SandboxJob.Limits(100, 128, 32, 1000),
                1_900_000_000L, "nonce-repo"));

        assertThat(result.status()).isEqualTo(SandboxJobStatus.SUCCEEDED);
        assertThat(result.outputPreview()).contains("class App");
    }

    @Test
    void rejectsArchiveReferenceOutsideProvisionedRoot() throws Exception {
        Path archiveRoot = Files.createDirectory(tempDir.resolve("archives"));
        RepositoryCommandExecutor executor = new RepositoryCommandExecutor(
                new WorkspaceArchiveResolver(archiveRoot),
                new RepositoryArchiveExtractor(
                        new RepositoryArchiveLimits(100, 1024 * 1024, 2 * 1024 * 1024), new RepositoryUrlPolicy()),
                new RepositoryReadCommandHandler(1024, 10, 1024),
                tempDir.resolve("work"));

        SandboxResult result = executor.execute(new SandboxJob(
                "repo-job", "../outside.zip", "ignored@sha256:abc", "repo.unpack", List.of(),
                new SandboxJob.Limits(100, 128, 32, 1000), 1_900_000_000L, "nonce-repo-2"));

        assertThat(result.status()).isEqualTo(SandboxJobStatus.REJECTED);
    }
}
