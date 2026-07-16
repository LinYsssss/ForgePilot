package com.example.reposage.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Fixed read-only repository command handlers. No model-provided executable is accepted. */
public final class RepositoryReadCommandHandler {

    private final int fileOutputBytes;
    private final int maxSearchResults;
    private final int searchOutputBytes;

    public RepositoryReadCommandHandler(int fileOutputBytes, int maxSearchResults, int searchOutputBytes) {
        if (fileOutputBytes <= 0 || maxSearchResults <= 0 || searchOutputBytes <= 0) {
            throw new IllegalArgumentException("output limits must be positive");
        }
        this.fileOutputBytes = fileOutputBytes;
        this.maxSearchResults = maxSearchResults;
        this.searchOutputBytes = searchOutputBytes;
    }

    public RepositoryCommandResult file(Path workspace, String relativePath) throws IOException {
        return file(workspace, relativePath, fileOutputBytes);
    }

    public RepositoryCommandResult file(Path workspace, String relativePath, int maxBytes) throws IOException {
        if (maxBytes <= 0 || maxBytes > fileOutputBytes) {
            throw new SecurityException("file output limit exceeds policy");
        }
        Path path = safePath(workspace, relativePath);
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            boolean truncated = bytes.length > maxBytes;
            int length = Math.min(bytes.length, maxBytes);
            return RepositoryCommandResult.success(new String(bytes, 0, length, StandardCharsets.UTF_8), truncated);
        }
    }

    public RepositoryCommandResult search(Path workspace, String query) throws IOException {
        return search(workspace, query, maxSearchResults, searchOutputBytes);
    }

    public RepositoryCommandResult search(Path workspace, String query, int resultLimit, int outputLimit) throws IOException {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("search query is required");
        }
        if (resultLimit <= 0 || resultLimit > maxSearchResults || outputLimit <= 0 || outputLimit > searchOutputBytes) {
            throw new SecurityException("search output limit exceeds policy");
        }
        List<Path> files;
        try (var paths = Files.walk(workspace.toRealPath(), 8)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(workspace.resolve(".git")))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        StringBuilder output = new StringBuilder();
        int results = 0;
        boolean truncated = false;
        for (Path path : files) {
            if (Files.size(path) > 4L * 1024 * 1024) {
                continue;
            }
            byte[] bytes = Files.readAllBytes(path);
            if (containsZero(bytes)) {
                continue;
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            String[] lines = text.split("\\R", -1);
            for (int index = 0; index < lines.length; index++) {
                if (!lines[index].contains(query)) {
                    continue;
                }
                String hit = workspace.toRealPath().relativize(path.toRealPath()).toString().replace('\\', '/')
                        + ":" + (index + 1) + ":" + lines[index] + "\n";
                if (results >= resultLimit || output.toString().getBytes(StandardCharsets.UTF_8).length
                        + hit.getBytes(StandardCharsets.UTF_8).length > outputLimit) {
                    truncated = true;
                    break;
                }
                output.append(hit);
                results++;
            }
            if (truncated) {
                break;
            }
        }
        return RepositoryCommandResult.success(output.toString(), truncated);
    }

    public RepositoryCommandResult diff(Path workspace) throws IOException {
        return diff(workspace, searchOutputBytes);
    }

    public RepositoryCommandResult diff(Path workspace, int maxBytes) throws IOException {
        if (maxBytes <= 0 || maxBytes > searchOutputBytes) {
            throw new SecurityException("diff output limit exceeds policy");
        }
        Path prepared = workspace.resolve(".reposage/review.diff");
        if (!Files.isRegularFile(prepared)) {
            return new RepositoryCommandResult(
                    SandboxJobStatus.ENVIRONMENT_INCOMPLETE, "", false, "prepared diff is unavailable");
        }
        byte[] bytes = Files.readAllBytes(prepared);
        boolean truncated = bytes.length > maxBytes;
        int length = Math.min(bytes.length, maxBytes);
        return RepositoryCommandResult.success(new String(bytes, 0, length, StandardCharsets.UTF_8), truncated);
    }

    private static Path safePath(Path workspace, String relativePath) throws IOException {
        Path root = workspace.toRealPath();
        if (relativePath == null || relativePath.isBlank() || relativePath.contains("\\")) {
            throw new SecurityException("repository path is invalid");
        }
        Path candidate = root.resolve(relativePath).normalize();
        if (!candidate.startsWith(root)) {
            throw new SecurityException("repository path escapes workspace");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(root) || !Files.isRegularFile(real)) {
            throw new SecurityException("repository path is unavailable");
        }
        return real;
    }

    private static boolean containsZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }
}
