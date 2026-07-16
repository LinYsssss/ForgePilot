package com.example.reposage.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Converts a signed job into a fixed, constrained {@code docker create} argument list. */
public final class ContainerPolicy {

    private static final String WORKSPACE_PREFIX = "workspace:";
    private static final String CONTAINER_WORKSPACE = "/workspace";

    private final Map<String, CommandSpec> commands;

    public ContainerPolicy(Map<String, CommandSpec> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new IllegalArgumentException("at least one command must be allowlisted");
        }
        this.commands = Map.copyOf(commands);
    }

    public ContainerLaunchSpec prepare(SandboxJob job, Path workspace) throws IOException {
        CommandSpec command = commands.get(job.commandId());
        if (command == null) {
            throw new SecurityException("commandId is not allowlisted: " + job.commandId());
        }
        validateImageDigest(job.imageDigest());
        Path realWorkspace = workspace.toRealPath();
        List<String> resolvedArguments = resolveArguments(job.args(), command.pathArgumentIndexes(), realWorkspace);
        SandboxJob.Limits limits = job.limits();

        List<String> create = new ArrayList<>();
        create.addAll(List.of(
                "--name", containerName(job.jobId()),
                "--network", "none",
                "--read-only",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--user", "65534:65534",
                "--cpus", String.format(Locale.ROOT, "%.3f", limits.cpuMillis() / 1000.0),
                "--memory", limits.memoryMb() + "m",
                "--pids-limit", Integer.toString(limits.pids()),
                "--mount", "type=bind,src=" + realWorkspace + ",dst=" + CONTAINER_WORKSPACE,
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m"));
        create.add(job.imageDigest());
        create.addAll(command.command());
        create.addAll(resolvedArguments);
        return new ContainerLaunchSpec(containerName(job.jobId()), create, limits.timeoutMs());
    }

    private static List<String> resolveArguments(List<String> arguments, List<Integer> pathIndexes, Path workspace)
            throws IOException {
        Set<Integer> indexes = new HashSet<>(pathIndexes);
        if (indexes.stream().anyMatch(index -> index >= arguments.size())) {
            throw new SecurityException("required workspace path argument is missing");
        }
        List<String> resolved = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (!indexes.contains(i)) {
                resolved.add(argument);
                continue;
            }
            if (argument == null || !argument.startsWith(WORKSPACE_PREFIX)) {
                throw new SecurityException("workspace path argument must use workspace: prefix");
            }
            String relative = argument.substring(WORKSPACE_PREFIX.length());
            Path candidate = workspace.resolve(relative).normalize();
            if (!candidate.startsWith(workspace)) {
                throw new SecurityException("workspace path escapes the job workspace");
            }
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(workspace)) {
                throw new SecurityException("workspace path resolves outside the job workspace");
            }
            resolved.add(CONTAINER_WORKSPACE + "/" + workspace.relativize(realCandidate).toString().replace('\\', '/'));
        }
        return resolved;
    }

    private static void validateImageDigest(String image) {
        if (image == null || !image.matches("[A-Za-z0-9._/-]+@sha256:[a-fA-F0-9]+")) {
            throw new SecurityException("imageDigest must be a pinned sha256 image reference");
        }
    }

    private static String containerName(String jobId) {
        String safe = jobId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
        if (safe.length() > 48) {
            safe = safe.substring(0, 48);
        }
        return "reposage-" + safe;
    }

    public record CommandSpec(List<String> command, List<Integer> pathArgumentIndexes) {

        public CommandSpec {
            if (command == null || command.isEmpty() || command.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("command must contain fixed non-blank arguments");
            }
            command = List.copyOf(command);
            pathArgumentIndexes = pathArgumentIndexes == null ? List.of() : List.copyOf(pathArgumentIndexes);
            if (pathArgumentIndexes.stream().anyMatch(index -> index == null || index < 0)) {
                throw new IllegalArgumentException("path argument indexes must be non-negative");
            }
        }
    }
}
