package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerSandboxExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void timeoutKillsAndAlwaysRemovesContainer() {
        FakeDockerClient docker = new FakeDockerClient(new DockerWaitResult(null, "partial", true, true));
        DockerSandboxExecutor executor = new DockerSandboxExecutor(policy(), docker, tempDir);

        SandboxResult result = executor.execute(job("job-timeout"));

        assertThat(result.status()).isEqualTo(SandboxJobStatus.TIMED_OUT);
        assertThat(docker.calls).containsExactly("create", "start:container-1", "wait:container-1", "kill:container-1", "remove:container-1");
    }

    @Test
    void successfulExecutionReturnsBoundedOutputAndCleansUp() {
        FakeDockerClient docker = new FakeDockerClient(new DockerWaitResult(0, "ok", false, false));
        DockerSandboxExecutor executor = new DockerSandboxExecutor(policy(), docker, tempDir);

        SandboxResult result = executor.execute(job("job-success"));

        assertThat(result.status()).isEqualTo(SandboxJobStatus.SUCCEEDED);
        assertThat(result.outputPreview()).isEqualTo("ok");
        assertThat(docker.calls).containsExactly("create", "start:container-1", "wait:container-1", "remove:container-1");
        executor.cancel("job-success");
        executor.cancel("job-success");
        assertThat(docker.calls).doesNotContain("kill:container-1");
    }

    @Test
    void activeCancellationKillsAndRemovesExactlyOnce() throws Exception {
        BlockingDockerClient docker = new BlockingDockerClient();
        DockerSandboxExecutor executor = new DockerSandboxExecutor(policy(), docker, tempDir);
        var thread = Executors.newSingleThreadExecutor();
        try {
            var result = thread.submit(() -> executor.execute(job("job-cancel")));
            assertThat(docker.waitStarted.await(5, TimeUnit.SECONDS)).isTrue();

            executor.cancel("job-cancel");
            executor.cancel("job-cancel");
            docker.releaseWait.countDown();
            result.get(5, TimeUnit.SECONDS);
        } finally {
            thread.shutdownNow();
        }

        assertThat(docker.calls.stream().filter("kill:container-1"::equals).count()).isEqualTo(1);
        assertThat(docker.calls.stream().filter("remove:container-1"::equals).count()).isEqualTo(1);
    }

    private static ContainerPolicy policy() {
        return new ContainerPolicy(Map.of(
                "sandbox.health", new ContainerPolicy.CommandSpec(List.of("/bin/true"), List.of())));
    }

    private static SandboxJob job(String jobId) {
        return new SandboxJob(
                jobId,
                "archive://workspace/job.tar.zst",
                "repo-tool@sha256:abcdef",
                "sandbox.health",
                List.of(),
                new SandboxJob.Limits(500, 256, 64, 1_000),
                1_900_000_000L,
                "nonce-" + jobId);
    }

    private static final class FakeDockerClient implements DockerClient {
        private final DockerWaitResult waitResult;
        private final List<String> calls = new ArrayList<>();

        private FakeDockerClient(DockerWaitResult waitResult) {
            this.waitResult = waitResult;
        }

        @Override
        public String create(ContainerLaunchSpec spec) {
            calls.add("create");
            return "container-1";
        }

        @Override
        public void start(String containerId) {
            calls.add("start:" + containerId);
        }

        @Override
        public DockerWaitResult waitFor(String containerId, long timeoutMs) {
            calls.add("wait:" + containerId);
            return waitResult;
        }

        @Override
        public void kill(String containerId) {
            calls.add("kill:" + containerId);
        }

        @Override
        public void remove(String containerId) {
            calls.add("remove:" + containerId);
        }
    }

    private static final class BlockingDockerClient implements DockerClient {
        private final List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch waitStarted = new CountDownLatch(1);
        private final CountDownLatch releaseWait = new CountDownLatch(1);

        @Override
        public String create(ContainerLaunchSpec spec) {
            calls.add("create");
            return "container-1";
        }

        @Override
        public void start(String containerId) {
            calls.add("start:" + containerId);
        }

        @Override
        public DockerWaitResult waitFor(String containerId, long timeoutMs) {
            calls.add("wait:" + containerId);
            waitStarted.countDown();
            try {
                releaseWait.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return new DockerWaitResult(137, "canceled", false, false);
        }

        @Override
        public void kill(String containerId) {
            calls.add("kill:" + containerId);
        }

        @Override
        public void remove(String containerId) {
            calls.add("remove:" + containerId);
        }
    }
}
