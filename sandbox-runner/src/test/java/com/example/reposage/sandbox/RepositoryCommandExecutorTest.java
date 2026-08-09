package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryCommandExecutorTest {

    @TempDir
    Path tempDir;

    private RepositoryCommandExecutor executor(Path archiveRoot) throws Exception {
        return new RepositoryCommandExecutor(
                new WorkspaceArchiveResolver(archiveRoot),
                new RepositoryArchiveExtractor(
                        new RepositoryArchiveLimits(100, 1024 * 1024, 2 * 1024 * 1024), new RepositoryUrlPolicy()),
                new RepositoryReadCommandHandler(1024, 10, 1024),
                tempDir.resolve("work"));
    }

    /**
     * Contract case: the job carries the backend encoder's real reference format and the archive
     * has the backend producer's real layout (tree + prepared {@code .reposage/review.diff}).
     * The previous suite only ever used bare fixture names like {@code repo.zip}, which is exactly
     * how the {@code workspace://} drift escaped every test.
     */
    @Test
    void acceptsBackendEncodedReferenceAndServesPreparedDiff() throws Exception {
        Path archiveRoot = Files.createDirectory(tempDir.resolve("archives"));
        String reference = WorkspaceArchiveReference.forAgentRun(7L, "ABCdef1234567");
        assertThat(reference).isEqualTo("agent-run-7-abcdef1234567.tar");
        String diff = "diff --git a/src/App.java b/src/App.java\n+++ b/src/App.java\n+class App {}\n";
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(
                Files.newOutputStream(archiveRoot.resolve(reference)))) {
            putTarEntry(tar, "src/App.java", "class App {}");
            putTarEntry(tar, ".reposage/review.diff", diff);
        }

        SandboxResult diffResult = executor(archiveRoot).execute(new SandboxJob(
                "prep-job", reference, "ignored@sha256:abc", "git.diff",
                List.of("main", "feature", "1024"), new SandboxJob.Limits(100, 128, 32, 1000),
                1_900_000_000L, "nonce-prep"));
        SandboxResult fileResult = executor(archiveRoot).execute(new SandboxJob(
                "prep-job-2", reference, "ignored@sha256:abc", "git.file",
                List.of("src/App.java"), new SandboxJob.Limits(100, 128, 32, 1000),
                1_900_000_000L, "nonce-prep-2"));

        assertThat(diffResult.status()).isEqualTo(SandboxJobStatus.SUCCEEDED);
        assertThat(diffResult.outputPreview()).isEqualTo(diff);
        assertThat(fileResult.status()).isEqualTo(SandboxJobStatus.SUCCEEDED);
        assertThat(fileResult.outputPreview()).contains("class App");
    }

    /** The historical drift format must keep failing loudly, as a rejection rather than an IO error. */
    @Test
    void rejectsLegacyWorkspaceSchemeReference() throws Exception {
        Path archiveRoot = Files.createDirectory(tempDir.resolve("archives"));

        SandboxResult result = executor(archiveRoot).execute(new SandboxJob(
                "legacy-job", "workspace://agent-run-7-abcdef1234567.tar", "ignored@sha256:abc",
                "repo.unpack", List.of(), new SandboxJob.Limits(100, 128, 32, 1000),
                1_900_000_000L, "nonce-legacy"));

        assertThat(result.status()).isEqualTo(SandboxJobStatus.REJECTED);
        assertThat(result.message()).contains("workspace archive reference is invalid");
    }

    @Test
    void routesSignedRepositoryJobThroughArchiveExtractorAndReadHandler() throws Exception {
        Path archiveRoot = Files.createDirectory(tempDir.resolve("archives"));
        Path archive = archiveRoot.resolve("repo.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("src/App.java"));
            output.write("class App {}".getBytes());
            output.closeEntry();
        }

        SandboxResult result = executor(archiveRoot).execute(new SandboxJob(
                "repo-job", "repo.zip", "ignored@sha256:abc", "git.file",
                List.of("src/App.java"), new SandboxJob.Limits(100, 128, 32, 1000),
                1_900_000_000L, "nonce-repo"));

        assertThat(result.status()).isEqualTo(SandboxJobStatus.SUCCEEDED);
        assertThat(result.outputPreview()).contains("class App");
    }

    @Test
    void rejectsArchiveReferenceOutsideProvisionedRoot() throws Exception {
        Path archiveRoot = Files.createDirectory(tempDir.resolve("archives"));

        SandboxResult result = executor(archiveRoot).execute(new SandboxJob(
                "repo-job", "../outside.zip", "ignored@sha256:abc", "repo.unpack", List.of(),
                new SandboxJob.Limits(100, 128, 32, 1000), 1_900_000_000L, "nonce-repo-2"));

        assertThat(result.status()).isEqualTo(SandboxJobStatus.REJECTED);
    }

    private static void putTarEntry(TarArchiveOutputStream tar, String name, String content)
            throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }
}

