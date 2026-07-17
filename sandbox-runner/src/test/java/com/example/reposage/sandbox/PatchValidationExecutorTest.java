package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchValidationExecutorTest {
    @TempDir Path tempDir;

    @Test
    void runsBaselineApplyAndPatchedValidationWithSameImageAndLimits() throws Exception {
        Path archives = Files.createDirectory(tempDir.resolve("archives"));
        createArchive(archives.resolve("repo.zip"));
        SequencedDocker docker = new SequencedDocker(
                new DockerWaitResult(1, "fingerprint-123", false, false),
                new DockerWaitResult(0, "check ok", false, false),
                new DockerWaitResult(0, "apply ok", false, false),
                new DockerWaitResult(0, "clean", false, false));
        PatchValidationExecutor executor = executor(archives, docker);

        SandboxResult result = executor.execute(job("head-sha", "java.maven.test", "fingerprint-123"));

        assertThat(result.status()).isEqualTo(SandboxJobStatus.SUCCEEDED);
        assertThat(result.outputPreview()).contains("\"applySucceeded\":true", "\"targetDisappeared\":true");
        assertThat(docker.commandIds).containsExactly(
                "java.maven.test", "patch.apply.check", "patch.apply", "java.maven.test");
        assertThat(docker.images).containsOnly("repo-tool@sha256:abcdef");
        assertThat(docker.timeouts).containsOnly(1000L);
    }

    @Test
    void rejectsStaleHeadBeforeStartingContainers() throws Exception {
        Path archives = Files.createDirectory(tempDir.resolve("archives"));
        createArchive(archives.resolve("repo.zip"));
        SequencedDocker docker = new SequencedDocker();
        PatchValidationExecutor executor = executor(archives, docker);

        SandboxResult result = executor.execute(job("different-head", "java.maven.test", "fingerprint-123"));

        assertThat(result.status()).isEqualTo(SandboxJobStatus.REJECTED);
        assertThat(result.message()).contains("stale head SHA");
        assertThat(docker.commandIds).isEmpty();
    }

    @Test
    void recordsApplyConflictAndDoesNotRunPatchedValidation() throws Exception {
        Path archives = Files.createDirectory(tempDir.resolve("archives"));
        createArchive(archives.resolve("repo.zip"));
        SequencedDocker docker = new SequencedDocker(
                new DockerWaitResult(1, "fingerprint-123", false, false),
                new DockerWaitResult(1, "patch conflict", false, false));
        PatchValidationExecutor executor = executor(archives, docker);

        SandboxResult result = executor.execute(job("head-sha", "java.maven.test", "fingerprint-123"));

        assertThat(result.status()).isEqualTo(SandboxJobStatus.FAILED);
        assertThat(result.outputPreview()).contains("\"applySucceeded\":false", "patch conflict");
        assertThat(docker.commandIds).containsExactly("java.maven.test", "patch.apply.check");
    }

    private PatchValidationExecutor executor(Path archives, SequencedDocker docker) throws Exception {
        return new PatchValidationExecutor(new WorkspaceArchiveResolver(archives),
                new RepositoryArchiveExtractor(new RepositoryArchiveLimits(100, 1024 * 1024, 2 * 1024 * 1024),
                        new RepositoryUrlPolicy()), new ContainerPolicy(SandboxCommandCatalog.commands()), docker,
                tempDir.resolve("work"), 4096);
    }

    private static SandboxJob job(String currentHead, String validationCommand, String fingerprint) {
        return new SandboxJob("patch-job", "repo.zip", "repo-tool@sha256:abcdef", "patch.validate",
                List.of("head-sha", currentHead, validationCommand, fingerprint),
                new SandboxJob.Limits(500, 256, 64, 1000), 1_900_000_000L, "nonce-patch");
    }

    private static void createArchive(Path archive) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("src/App.java")); output.write("class App {}".getBytes()); output.closeEntry();
            output.putNextEntry(new ZipEntry(".reposage/candidate.patch")); output.write("patch".getBytes()); output.closeEntry();
        }
    }

    private static final class SequencedDocker implements DockerClient {
        private final Queue<DockerWaitResult> waits = new ArrayDeque<>();
        private final List<String> commandIds = new ArrayList<>();
        private final List<String> images = new ArrayList<>();
        private final List<Long> timeouts = new ArrayList<>();
        private int sequence;
        SequencedDocker(DockerWaitResult... waits) { this.waits.addAll(List.of(waits)); }
        public String create(ContainerLaunchSpec spec) {
            String command = String.join(" ", spec.createArguments());
            commandIds.add(command.contains("apply --check") ? "patch.apply.check"
                    : command.contains(" apply ") ? "patch.apply" : "java.maven.test");
            images.add(spec.createArguments().stream().filter(v -> v.contains("@sha256:")).findFirst().orElseThrow());
            timeouts.add(spec.timeoutMs()); return "container-" + (++sequence);
        }
        public void start(String id) {}
        public DockerWaitResult waitFor(String id, long timeout) { return waits.remove(); }
        public void kill(String id) {}
        public void remove(String id) {}
    }
}
