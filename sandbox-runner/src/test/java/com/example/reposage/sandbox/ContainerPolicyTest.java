package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsConstrainedCreateArgumentsFromWhitelistedCommand() throws IOException {
        ContainerPolicy policy = policy();
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("input.txt"), "content");
        SandboxJob job = job("safe.read", List.of("workspace:input.txt"));

        ContainerLaunchSpec spec = policy.prepare(job, workspace);

        assertThat(spec.createArguments()).containsSequence("--network", "none");
        assertThat(spec.createArguments()).contains("--read-only");
        assertThat(spec.createArguments()).containsSequence("--user", "65534:65534");
        assertThat(spec.createArguments()).containsSequence("--cpus", "1.000");
        assertThat(spec.createArguments()).containsSequence("--memory", "512m");
        assertThat(spec.createArguments()).containsSequence("--pids-limit", "128");
        assertThat(spec.createArguments()).containsSequence(
                "--mount", "type=bind,src=" + workspace.toRealPath() + ",dst=/workspace");
        assertThat(spec.createArguments()).containsSequence("--tmpfs", "/tmp:rw,noexec,nosuid,size=64m");
        assertThat(spec.createArguments()).endsWith(
                "repo-tool@sha256:abcdef", "/usr/bin/safe-read", "/workspace/input.txt");
        assertThat(spec.timeoutMs()).isEqualTo(60_000);
    }

    @Test
    void rejectsUnknownCommandAndPathTraversal() throws IOException {
        ContainerPolicy policy = policy();
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));

        assertThatThrownBy(() -> policy.prepare(job("rm -rf /", List.of()), workspace))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("commandId");
        assertThatThrownBy(() -> policy.prepare(job("safe.read", List.of("workspace:../secret")), workspace))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("workspace");
    }

    @Test
    void rejectsSymlinkThatEscapesWorkspaceWhenPlatformSupportsSymlinks() throws IOException {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            ContainerPolicy policy = policy();
            Path workspace = Files.createDirectory(fileSystem.getPath("/workspace"));
            Path outside = Files.writeString(fileSystem.getPath("/outside.txt"), "secret");
            Files.createSymbolicLink(workspace.resolve("escape.txt"), outside);

            assertThatThrownBy(() -> policy.prepare(job("safe.read", List.of("workspace:escape.txt")), workspace))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("workspace");
        }
    }

    private static ContainerPolicy policy() {
        return new ContainerPolicy(Map.of(
                "safe.read", new ContainerPolicy.CommandSpec(List.of("/usr/bin/safe-read"), List.of(0)),
                "sandbox.health", new ContainerPolicy.CommandSpec(List.of("/bin/true"), List.of())));
    }

    private static SandboxJob job(String commandId, List<String> args) {
        return new SandboxJob(
                "job-1",
                "archive://workspace/job.tar.zst",
                "repo-tool@sha256:abcdef",
                commandId,
                args,
                new SandboxJob.Limits(1000, 512, 128, 60_000),
                1_900_000_000L,
                "nonce-1");
    }
}
