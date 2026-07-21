package com.example.codereview.git;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.CryptoService;
import com.example.codereview.repo.CodeRepositoryEntity;
import com.example.codereview.repo.RepositoryDtos.CommitDiffResponse;
import com.example.codereview.repo.RepositoryDtos.CommitResponse;
import com.example.codereview.repo.RepositoryDtos.DiffFileResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GitCliService {

    private final Path workRoot = Path.of(".work", "repos");
    private final ConcurrentMap<Long, Object> repositoryLocks = new ConcurrentHashMap<>();
    private final CryptoService cryptoService;
    private final boolean allowLocalRepoPath;

    public GitCliService(CryptoService cryptoService,
                         @Value("${app.git.allow-local-path:false}") boolean allowLocalRepoPath) {
        this.cryptoService = cryptoService;
        this.allowLocalRepoPath = allowLocalRepoPath;
    }

    public Path ensureClone(CodeRepositoryEntity repository) {
        Long lockKey = repository.getId() == null ? -1L : repository.getId();
        Object lock = repositoryLocks.computeIfAbsent(lockKey, key -> new Object());
        synchronized (lock) {
            return ensureCloneLocked(repository);
        }
    }

    public void deleteWorkingCopy(Long repositoryId) {
        if (repositoryId == null) {
            return;
        }
        Object lock = repositoryLocks.computeIfAbsent(repositoryId, key -> new Object());
        synchronized (lock) {
            deleteWorkingCopyLocked(repositoryId);
        }
        repositoryLocks.remove(repositoryId);
    }

    private Path ensureCloneLocked(CodeRepositoryEntity repository) {
        GitInputValidator.requireSafeRepoUrl(repository.getRepoUrl(), allowLocalRepoPath);
        GitInputValidator.requireSafeRef(repository.getDefaultBranch(), "默认分支");
        try {
            Files.createDirectories(workRoot);
            Path localPath = resolveRepositoryPath(repository.getId());
            if (Files.exists(localPath.resolve(".git"))) {
                if (remoteMatches(localPath, repository)) {
                    run(localPath, repository, "fetch", "--all", "--prune");
                    run(localPath, repository, "checkout", repository.getDefaultBranch());
                    run(localPath, repository, "pull", "--ff-only", "origin", repository.getDefaultBranch());
                    return localPath;
                }
                // 已存在的工作副本指向的是另一个远端(改绑了仓库,或工作卷比数据库存活更久导致 id 复用),
                // 丢弃后按当前 repoUrl 重新克隆,避免读到别的仓库的历史。
                deleteWorkingCopyLocked(repository.getId());
            }
            run(workRoot.toAbsolutePath(), repository, "clone", repository.getRepoUrl(), localPath.toString());
            run(localPath, repository, "checkout", repository.getDefaultBranch());
            return localPath;
        } catch (IOException ex) {
            throw new BusinessException(6001, "准备仓库目录失败");
        }
    }

    private boolean remoteMatches(Path localPath, CodeRepositoryEntity repository) {
        String actual = currentRemoteUrl(localPath, repository);
        return actual != null && normalizeRemote(actual).equals(normalizeRemote(repository.getRepoUrl()));
    }

    private String currentRemoteUrl(Path localPath, CodeRepositoryEntity repository) {
        try {
            return run(localPath, repository, "remote", "get-url", "origin").trim();
        } catch (BusinessException ex) {
            return null;
        }
    }

    private String normalizeRemote(String url) {
        if (url == null) {
            return "";
        }
        String normalized = url.trim().replace('\\', '/').replaceAll("/+$", "");
        if (normalized.regionMatches(true, normalized.length() - 4, ".git", 0, 4)) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        return normalized;
    }

    public List<CommitResponse> listCommits(CodeRepositoryEntity repository, int limit) {
        GitInputValidator.requireSafeRef(repository.getDefaultBranch(), "默认分支");
        int safeLimit = Math.max(1, Math.min(limit, 200));
        Path localPath = ensureClone(repository);
        String output = run(
                localPath,
                repository,
                "log",
                "--max-count=" + safeLimit,
                "--pretty=format:%H%x1f%P%x1f%an%x1f%ae%x1f%ct%x1f%s",
                repository.getDefaultBranch()
        );
        List<CommitResponse> commits = new ArrayList<>();
        if (output.isBlank()) {
            return commits;
        }
        for (String line : output.split("\\R")) {
            String[] parts = line.split("\\u001f", -1);
            if (parts.length >= 6) {
                String parent = parts[1].contains(" ") ? parts[1].split(" ")[0] : parts[1];
                commits.add(new CommitResponse(
                        parts[0],
                        parent,
                        parts[2],
                        parts[3],
                        parts[5],
                        Instant.ofEpochSecond(Long.parseLong(parts[4]))
                ));
            }
        }
        return commits;
    }

    public CommitDiffResponse diff(CodeRepositoryEntity repository, String commitId, String baseCommitId) {
        GitInputValidator.requireSafeRef(commitId, "Commit");
        if (baseCommitId != null && !baseCommitId.isBlank()) {
            GitInputValidator.requireSafeRef(baseCommitId, "Base Commit");
        }
        Path localPath = ensureClone(repository);
        String base = baseCommitId;
        if (base == null || base.isBlank()) {
            base = resolveParentOrEmptyTree(localPath, repository, commitId);
        }
        String rawDiff = run(localPath, repository, "diff", "--find-renames", base, commitId, "--");
        List<DiffFileResponse> files = parseFiles(rawDiff);
        return new CommitDiffResponse(commitId, base, files, rawDiff);
    }

    private String resolveParentOrEmptyTree(Path localPath, CodeRepositoryEntity repository, String commitId) {
        String parents = run(localPath, repository, "rev-list", "--parents", "-n", "1", commitId, "--").trim();
        String[] parts = parents.split("\\s+");
        if (parts.length >= 2) {
            return parts[1];
        }
        return "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
    }

    private List<DiffFileResponse> parseFiles(String rawDiff) {
        List<DiffFileResponse> files = new ArrayList<>();
        if (rawDiff.isBlank()) {
            return files;
        }
        String[] chunks = rawDiff.split("(?m)^diff --git ");
        for (String chunk : chunks) {
            if (chunk.isBlank()) {
                continue;
            }
            String diff = "diff --git " + chunk;
            String firstLine = diff.lines().findFirst().orElse("");
            String filePath = firstLine.replaceFirst("^diff --git a/", "");
            int bIndex = filePath.indexOf(" b/");
            if (bIndex >= 0) {
                filePath = filePath.substring(bIndex + 3).trim();
            }
            int additions = 0;
            int deletions = 0;
            for (String line : diff.split("\\R")) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    additions++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    deletions++;
                }
            }
            files.add(new DiffFileResponse(filePath, "MODIFIED", additions, deletions, diff));
        }
        return files;
    }

    private void deleteWorkingCopyLocked(Long repositoryId) {
        Path repoPath = resolveRepositoryPath(repositoryId);
        if (Files.notExists(repoPath)) {
            return;
        }
        ensureWithinWorkRoot(repoPath);
        try (Stream<Path> paths = Files.walk(repoPath)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new IllegalStateException("删除仓库工作目录失败: " + path, ex);
                }
            });
        } catch (IOException ex) {
            throw new IllegalStateException("删除仓库工作目录失败", ex);
        }
    }

    private Path resolveRepositoryPath(Long repositoryId) {
        return workRoot.resolve("repo-" + repositoryId).toAbsolutePath().normalize();
    }

    private void ensureWithinWorkRoot(Path candidate) {
        Path absoluteWorkRoot = workRoot.toAbsolutePath().normalize();
        if (!candidate.normalize().startsWith(absoluteWorkRoot)) {
            throw new IllegalStateException("仓库工作目录越界: " + candidate);
        }
    }

    private String[] gitCommand(CodeRepositoryEntity repository, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        addSafeDirectoryOptions(command, repository.getRepoUrl());
        command.addAll(List.of(args));
        return command.toArray(String[]::new);
    }

    private void addSafeDirectoryOptions(List<String> command, String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank() || repoUrl.toLowerCase().startsWith("http") || repoUrl.startsWith("git@")) {
            return;
        }
        String normalized = repoUrl.replace('\\', '/').replaceAll("/+$", "");
        command.add("-c");
        command.add("safe.directory=" + normalized);
        command.add("-c");
        command.add("safe.directory=" + normalized + "/.git");
    }

    private String askPassUsername(CodeRepositoryEntity repository) {
        return switch ((repository.getProvider() == null ? "" : repository.getProvider()).toUpperCase()) {
            case "GITLAB", "GITEE" -> "oauth2";
            default -> "x-access-token";
        };
    }

    private boolean shouldUseToken(String repoUrl, String token) {
        return token != null
                && !token.isBlank()
                && repoUrl != null
                && repoUrl.toLowerCase().startsWith("http");
    }

    private String run(Path directory, CodeRepositoryEntity repository, String... args) {
        Path askPassScript = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(gitCommand(repository, args))
                    .directory(directory.toFile())
                    .redirectErrorStream(true);
            builder.environment().put("GIT_ALLOW_PROTOCOL", repository.getRepoUrl().toLowerCase().startsWith("http")
                    ? "http:https"
                    : "http:https:file");
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            String token = cryptoService.decrypt(repository.getAccessTokenCiphertext());
            if (shouldUseToken(repository.getRepoUrl(), token)) {
                AskPassConfig askPass = createAskPass();
                askPassScript = askPass.scriptPath();
                builder.environment().put("GIT_ASKPASS", askPass.command());
                builder.environment().put("REPOSAGE_GIT_USERNAME", askPassUsername(repository));
                builder.environment().put("REPOSAGE_GIT_PASSWORD", token);
            }
            Process process = builder.start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(6001, "Git 命令执行超时");
            }
            if (process.exitValue() != 0) {
                throw new BusinessException(6001, "Git 命令执行失败: " + output);
            }
            return output;
        } catch (IOException ex) {
            throw new BusinessException(6001, "无法执行 git 命令，请确认本机已安装 Git");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(6001, "Git 命令被中断");
        } finally {
            if (askPassScript != null) {
                try {
                    Files.deleteIfExists(askPassScript);
                } catch (IOException ignored) {
                    // Best-effort cleanup for ephemeral askpass helpers.
                }
            }
        }
    }

    private AskPassConfig createAskPass() throws IOException {
        Files.createDirectories(workRoot);
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            Path scriptPath = Files.createTempFile(workRoot, "git-askpass-", ".ps1");
            String script = """
                    param([string]$prompt)
                    if ($prompt -match 'Username') {
                      [Console]::Out.WriteLine($env:REPOSAGE_GIT_USERNAME)
                    } else {
                      [Console]::Out.WriteLine($env:REPOSAGE_GIT_PASSWORD)
                    }
                    """;
            Files.writeString(scriptPath, script, StandardCharsets.UTF_8);
            String command = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File \"" + scriptPath + "\"";
            return new AskPassConfig(scriptPath, command);
        }
        Path scriptPath = Files.createTempFile(workRoot, "git-askpass-", ".sh");
        String script = """
                #!/bin/sh
                case "$1" in
                  *Username*) printf '%s\\n' "$REPOSAGE_GIT_USERNAME" ;;
                  *) printf '%s\\n' "$REPOSAGE_GIT_PASSWORD" ;;
                esac
                """;
        Files.writeString(scriptPath, script, StandardCharsets.UTF_8);
        scriptPath.toFile().setExecutable(true);
        return new AskPassConfig(scriptPath, scriptPath.toAbsolutePath().toString());
    }

    private record AskPassConfig(Path scriptPath, String command) {
    }
}
