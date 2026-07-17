package com.example.reposage.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class PatchValidationExecutor implements SandboxExecutor {
    private final WorkspaceArchiveResolver archives;
    private final RepositoryArchiveExtractor extractor;
    private final ContainerPolicy policy;
    private final DockerClient docker;
    private final Path workRoot;
    private final int maxOutputBytes;

    @Autowired
    public PatchValidationExecutor(WorkspaceArchiveResolver archives, RepositoryArchiveExtractor extractor,
                                   ContainerPolicy policy, DockerClient docker,
                                   @Value("${app.sandbox.work-root:/app/.work}") String workRoot) {
        this(archives, extractor, policy, docker, Path.of(workRoot), 65_536);
    }

    PatchValidationExecutor(WorkspaceArchiveResolver archives, RepositoryArchiveExtractor extractor,
                            ContainerPolicy policy, DockerClient docker, Path workRoot, int maxOutputBytes) {
        this.archives = archives; this.extractor = extractor; this.policy = policy; this.docker = docker;
        this.workRoot = workRoot.toAbsolutePath().normalize(); this.maxOutputBytes = maxOutputBytes;
    }

    @Override
    public SandboxResult execute(SandboxJob job) {
        Path workspace = null;
        try {
            if (!"patch.validate".equals(job.commandId()) || job.args().size() != 4) {
                throw new SecurityException("patch validation arguments are invalid");
            }
            String boundHead = required(job.args().get(0), "bound head SHA");
            String currentHead = required(job.args().get(1), "current head SHA");
            String validationCommand = required(job.args().get(2), "validation command ID");
            String target = job.args().get(3) == null ? "" : job.args().get(3).trim();
            if (!boundHead.equals(currentHead)) {
                throw new SecurityException("stale head SHA");
            }
            if (validationCommand.startsWith("patch.") || validationCommand.equals("sandbox.health")) {
                throw new SecurityException("validation command ID is not allowed");
            }
            Files.createDirectories(workRoot);
            workspace = Files.createTempDirectory(workRoot, "patch-");
            extractor.extract(archives.resolve(job.workspaceArchiveRef()), workspace);
            if (!Files.isRegularFile(workspace.resolve(".reposage/candidate.patch"))) {
                return failure(job, SandboxJobStatus.ENVIRONMENT_INCOMPLETE, "candidate patch is unavailable");
            }

            DockerWaitResult baseline = run(job, workspace, validationCommand, "baseline");
            if (baseline.timedOut()) return timedOut(job, "baseline validation timed out", baseline);
            DockerWaitResult check = run(job, workspace, "patch.apply.check", "apply-check");
            if (check.timedOut()) return timedOut(job, "patch apply check timed out", check);
            if (!success(check)) return validationResult(job, SandboxJobStatus.FAILED, baseline, check, null, null, target);
            DockerWaitResult apply = run(job, workspace, "patch.apply", "apply");
            if (apply.timedOut()) return timedOut(job, "patch apply timed out", apply);
            if (!success(apply)) return validationResult(job, SandboxJobStatus.FAILED, baseline, apply, null, null, target);
            DockerWaitResult patched = run(job, workspace, validationCommand, "patched");
            if (patched.timedOut()) return timedOut(job, "patched validation timed out", patched);
            boolean targetDisappeared = target.isBlank()
                    ? !success(baseline) && success(patched)
                    : baseline.output().contains(target) && !patched.output().contains(target);
            SandboxJobStatus status = success(patched) && targetDisappeared
                    ? SandboxJobStatus.SUCCEEDED : SandboxJobStatus.FAILED;
            return validationResult(job, status, baseline, apply, patched, targetDisappeared, target);
        } catch (SecurityException ex) {
            return failure(job, SandboxJobStatus.REJECTED, ex.getMessage());
        } catch (DockerUnavailableException | IOException ex) {
            return failure(job, SandboxJobStatus.ENVIRONMENT_INCOMPLETE, ex.getMessage());
        } catch (RuntimeException ex) {
            return failure(job, SandboxJobStatus.FAILED, ex.getMessage());
        } finally {
            delete(workspace);
        }
    }

    private DockerWaitResult run(SandboxJob original, Path workspace, String commandId, String suffix) {
        SandboxJob derived = new SandboxJob(original.jobId() + "-" + suffix, original.workspaceArchiveRef(),
                original.imageDigest(), commandId, List.of(), original.limits(), original.expiryEpochSeconds(),
                original.nonce() + "-" + suffix);
        ContainerLaunchSpec spec;
        try { spec = policy.prepare(derived, workspace); }
        catch (IOException ex) { throw new IllegalStateException(ex); }
        String container = docker.create(spec);
        try {
            docker.start(container);
            DockerWaitResult result = docker.waitFor(container, spec.timeoutMs());
            if (result.timedOut()) docker.kill(container);
            return result;
        } finally { docker.remove(container); }
    }

    private SandboxResult validationResult(SandboxJob job, SandboxJobStatus status, DockerWaitResult baseline,
                                           DockerWaitResult apply, DockerWaitResult patched,
                                           Boolean targetDisappeared, String target) {
        boolean applied = success(apply);
        String json = "{\"baselineExitCode\":" + number(baseline.exitCode())
                + ",\"applySucceeded\":" + applied
                + ",\"patchedExitCode\":" + (patched == null ? "null" : number(patched.exitCode()))
                + ",\"buildOrTestPassed\":" + (patched != null && success(patched))
                + ",\"targetDisappeared\":" + (targetDisappeared == null ? "false" : targetDisappeared)
                + ",\"target\":\"" + escape(target) + "\",\"baselineLog\":\"" + escape(baseline.output())
                + "\",\"applyLog\":\"" + escape(apply.output()) + "\",\"patchedLog\":\""
                + escape(patched == null ? "" : patched.output()) + "\"}";
        boolean truncated = json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxOutputBytes;
        if (truncated) json = new String(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0,
                maxOutputBytes, java.nio.charset.StandardCharsets.UTF_8);
        return new SandboxResult(job.jobId(), status, patched == null ? apply.exitCode() : patched.exitCode(),
                json, truncated, status == SandboxJobStatus.SUCCEEDED ? null : "patch validation failed");
    }

    private static SandboxResult timedOut(SandboxJob job, String message, DockerWaitResult wait) {
        return new SandboxResult(job.jobId(), SandboxJobStatus.TIMED_OUT, null, wait.output(), wait.truncated(), message);
    }
    private static SandboxResult failure(SandboxJob job, SandboxJobStatus status, String message) {
        return new SandboxResult(job.jobId(), status, null, "", false, message);
    }
    private static boolean success(DockerWaitResult result) { return result.exitCode() != null && result.exitCode() == 0; }
    private static String number(Integer value) { return value == null ? "null" : value.toString(); }
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new SecurityException(name + " is required"); return value;
    }
    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }
    private static void delete(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
