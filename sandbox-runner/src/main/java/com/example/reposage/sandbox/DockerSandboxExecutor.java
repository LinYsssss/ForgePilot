package com.example.reposage.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DockerSandboxExecutor implements SandboxExecutor {

    private final ContainerPolicy policy;
    private final DockerClient docker;
    private final Path workRoot;
    private final Map<String, String> activeContainers = new ConcurrentHashMap<>();

    @Autowired
    public DockerSandboxExecutor(
            ContainerPolicy policy,
            DockerClient docker,
            @Value("${app.sandbox.work-root:/app/.work}") String workRoot) {
        this(policy, docker, Path.of(workRoot));
    }

    DockerSandboxExecutor(ContainerPolicy policy, DockerClient docker, Path workRoot) {
        this.policy = policy;
        this.docker = docker;
        this.workRoot = workRoot.toAbsolutePath().normalize();
    }

    @Override
    public SandboxResult execute(SandboxJob job) {
        Path workspace = null;
        String containerId = null;
        try {
            Files.createDirectories(workRoot);
            workspace = Files.createTempDirectory(workRoot, "job-");
            ContainerLaunchSpec spec = policy.prepare(job, workspace);
            containerId = docker.create(spec);
            activeContainers.put(job.jobId(), containerId);
            docker.start(containerId);
            DockerWaitResult wait = docker.waitFor(containerId, spec.timeoutMs());
            if (wait.timedOut()) {
                safeKill(containerId);
                return result(job, SandboxJobStatus.TIMED_OUT, null, wait, "sandbox execution timed out");
            }
            SandboxJobStatus status = wait.exitCode() != null && wait.exitCode() == 0
                    ? SandboxJobStatus.SUCCEEDED
                    : SandboxJobStatus.FAILED;
            return result(job, status, wait.exitCode(), wait, null);
        } catch (SecurityException ex) {
            return failure(job, SandboxJobStatus.REJECTED, ex.getMessage());
        } catch (DockerUnavailableException ex) {
            return failure(job, SandboxJobStatus.ENVIRONMENT_INCOMPLETE, ex.getMessage());
        } catch (Exception ex) {
            return failure(job, SandboxJobStatus.FAILED, ex.getMessage());
        } finally {
            cleanup(job.jobId(), containerId, workspace);
        }
    }

    public void cancel(String jobId) {
        String containerId = activeContainers.remove(jobId);
        if (containerId == null) {
            return;
        }
        try {
            safeKill(containerId);
        } finally {
            safeRemove(containerId);
        }
    }

    private void cleanup(String jobId, String containerId, Path workspace) {
        if (containerId != null && activeContainers.remove(jobId, containerId)) {
            safeRemove(containerId);
        }
        deleteWorkspace(workspace);
    }

    private void safeKill(String containerId) {
        try {
            docker.kill(containerId);
        } catch (RuntimeException ignored) {
            // 容器可能已经退出,或被并发的取消操作删掉了。
        }
    }

    private void safeRemove(String containerId) {
        try {
            docker.remove(containerId);
        } catch (RuntimeException ignored) {
            // 清理是幂等且尽力而为的;守护进程侧的失败留给后续的清道夫重试。
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
                    // 清理尽力而为且幂等;残留物留给后续的清道夫重试。
                }
            });
        } catch (IOException ignored) {
            // 尽力而为的清理,失败不影响主流程。
        }
    }

    private static SandboxResult result(
            SandboxJob job,
            SandboxJobStatus status,
            Integer exitCode,
            DockerWaitResult wait,
            String message) {
        return new SandboxResult(job.jobId(), status, exitCode, wait.output(), wait.truncated(), message);
    }

    private static SandboxResult failure(SandboxJob job, SandboxJobStatus status, String message) {
        return new SandboxResult(job.jobId(), status, null, "", false, message);
    }
}
