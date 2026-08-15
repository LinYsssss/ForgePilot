package com.example.reposage.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Docker CLI 适配器。每条命令都由固定 token 拼装,全程不经过 shell——没有可注入的字符串。 */
public final class DockerCliClient implements DockerClient {

    private static final int MAX_OUTPUT_BYTES = 65_536;

    @Override
    public String create(ContainerLaunchSpec spec) {
        List<String> command = new ArrayList<>(List.of("docker", "create"));
        command.addAll(spec.createArguments());
        CommandResult result = run(command, Duration.ofSeconds(30));
        requireSuccess(result, "docker create");
        return result.output().trim();
    }

    @Override
    public void start(String containerId) {
        CommandResult result = run(List.of("docker", "start", containerId), Duration.ofSeconds(30));
        requireSuccess(result, "docker start");
    }

    @Override
    public DockerWaitResult waitFor(String containerId, long timeoutMs) {
        CommandResult wait = run(List.of("docker", "wait", containerId), Duration.ofMillis(timeoutMs));
        if (wait.timedOut()) {
            CommandResult logs = logs(containerId);
            return new DockerWaitResult(null, logs.output(), logs.truncated(), true);
        }
        requireSuccess(wait, "docker wait");
        Integer exitCode;
        try {
            exitCode = Integer.valueOf(wait.output().trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("docker wait returned an invalid exit code", ex);
        }
        CommandResult logs = logs(containerId);
        return new DockerWaitResult(exitCode, logs.output(), logs.truncated(), false);
    }

    @Override
    public void kill(String containerId) {
        run(List.of("docker", "kill", containerId), Duration.ofSeconds(15));
    }

    @Override
    public void remove(String containerId) {
        run(List.of("docker", "rm", "-f", containerId), Duration.ofSeconds(15));
    }

    private static CommandResult logs(String containerId) {
        return run(List.of("docker", "logs", containerId), Duration.ofSeconds(15));
    }

    private static CommandResult run(List<String> command, Duration timeout) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return new CommandResult(-1, "", false, true);
            }
            byte[] bytes = process.getInputStream().readAllBytes();
            boolean truncated = bytes.length > MAX_OUTPUT_BYTES;
            int length = Math.min(bytes.length, MAX_OUTPUT_BYTES);
            return new CommandResult(
                    process.exitValue(), new String(bytes, 0, length, StandardCharsets.UTF_8), truncated, false);
        } catch (IOException ex) {
            throw new DockerUnavailableException("docker CLI is unavailable", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("docker command interrupted", ex);
        }
    }

    private static void requireSuccess(CommandResult result, String operation) {
        if (result.exitCode() != 0) {
            throw new IllegalStateException(operation + " failed: " + result.output());
        }
    }

    private record CommandResult(int exitCode, String output, boolean truncated, boolean timedOut) {
    }
}
