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
     * 契约用例:job 里带的是 backend 编码器的**真实**引用格式,归档也是 backend 生产方的**真实**
     * 布局(仓库树 + 已备好的 {@code .reposage/review.diff})。
     * 旧用例一律只用 {@code repo.zip} 这类裸夹具名——{@code workspace://} 那次漂移能躲过全部测试,
     * 正是因为这个。
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

    /** 那个历史漂移格式必须继续**响亮地**失败:要以「拒绝」的形式,而不是退化成一个 IO 错误。 */
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

