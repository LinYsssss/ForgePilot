package com.example.reposage.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/** Computes content-addressed dependency cache keys and resolves completed cache directories. */
public final class DependencyCacheManager {

    private static final int MAX_LOCK_FILES = 32;
    private static final long MAX_LOCK_FILE_BYTES = 4L * 1024 * 1024;
    private static final String COMPLETE_MARKER = ".complete";
    private static final Map<String, String> LOCK_FILES = Map.ofEntries(
            Map.entry("pom.xml", "maven"),
            Map.entry("gradle.lockfile", "gradle"),
            Map.entry("gradle-wrapper.properties", "gradle"),
            Map.entry("requirements.txt", "python"),
            Map.entry("requirements.lock", "python"),
            Map.entry("poetry.lock", "python"),
            Map.entry("pipfile.lock", "python"),
            Map.entry("package-lock.json", "npm"),
            Map.entry("npm-shrinkwrap.json", "npm"),
            Map.entry("pnpm-lock.yaml", "node"),
            Map.entry("yarn.lock", "node"));

    private final Path cacheRoot;
    private final DependencyPreparationPolicy policy;

    public DependencyCacheManager(Path cacheRoot, DependencyPreparationPolicy policy) {
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
        this.policy = policy;
    }

    public Optional<DependencyCacheKey> computeCacheKey(Path workspace) throws IOException {
        Path realWorkspace = workspace.toRealPath();
        List<LockFile> locks = new ArrayList<>();
        try (var paths = Files.walk(realWorkspace, 8)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String ecosystem = LOCK_FILES.get(path.getFileName().toString().toLowerCase());
                if (ecosystem != null) {
                    try {
                        Path realPath = path.toRealPath();
                        if (!realPath.startsWith(realWorkspace)) {
                            throw new SecurityException("dependency lock file escapes workspace: " + path);
                        }
                        locks.add(new LockFile(
                                ecosystem,
                                realWorkspace.relativize(realPath).toString().replace('\\', '/'),
                                realPath));
                    } catch (IOException ex) {
                        throw new IllegalStateException("cannot resolve dependency lock file: " + path, ex);
                    }
                }
            });
        }
        if (locks.isEmpty()) {
            return Optional.empty();
        }
        if (locks.size() > MAX_LOCK_FILES) {
            throw new SecurityException("too many dependency lock files");
        }
        locks.sort(Comparator.comparing(LockFile::ecosystem).thenComparing(LockFile::relativePath));
        MessageDigest digest = sha256();
        TreeSet<String> ecosystems = new TreeSet<>();
        digest.update("reposage-dependency-cache-v1\n".getBytes(StandardCharsets.UTF_8));
        for (LockFile lock : locks) {
            long size = Files.size(lock.path());
            if (size > MAX_LOCK_FILE_BYTES) {
                throw new SecurityException("dependency lock file is too large: " + lock.relativePath());
            }
            ecosystems.add(lock.ecosystem());
            digest.update(lock.ecosystem().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(lock.relativePath().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(lock.path()));
            digest.update((byte) '\n');
        }
        List<String> ecosystemList = List.copyOf(ecosystems);
        String value = "v1-" + String.join("+", ecosystemList) + "-" + HexFormat.of().formatHex(digest.digest());
        return Optional.of(new DependencyCacheKey(value, ecosystemList));
    }

    public DependencyPreparationRequest validatePreparation(DependencyPreparationRequest request) {
        return policy.validatePreparation(request);
    }

    public DependencyCacheMount readOnlyMount(Path cache, String target) throws IOException {
        return new DependencyCacheMount(cache.toRealPath(), target, true, "none");
    }

    public DependencyResolution resolve(DependencyCacheKey key) {
        Path path = cacheRoot.resolve(key.value()).normalize();
        if (!path.startsWith(cacheRoot)) {
            throw new SecurityException("dependency cache key escaped cache root");
        }
        if (Files.isDirectory(path) && Files.isRegularFile(path.resolve(COMPLETE_MARKER))) {
            return new DependencyResolution(true, SandboxJobStatus.SUCCEEDED, path, false, "dependency cache available");
        }
        return new DependencyResolution(
                false,
                SandboxJobStatus.ENVIRONMENT_INCOMPLETE,
                null,
                false,
                "dependency cache is unavailable; run an allowlisted preparation job");
    }

    public Path markPrepared(DependencyCacheKey key) throws IOException {
        Path path = cacheRoot.resolve(key.value()).normalize();
        if (!path.startsWith(cacheRoot)) {
            throw new SecurityException("dependency cache key escaped cache root");
        }
        Files.createDirectories(path);
        Files.writeString(path.resolve(COMPLETE_MARKER), key.value(), StandardCharsets.UTF_8);
        return path;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record LockFile(String ecosystem, String relativePath, Path path) {
    }
}
