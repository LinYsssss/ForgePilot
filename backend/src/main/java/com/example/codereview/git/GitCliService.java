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
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class GitCliService {

    private final Path workRoot = Path.of(".work", "repos");
    private final ConcurrentMap<Long, Object> repositoryLocks = new ConcurrentHashMap<>();
    private final CryptoService cryptoService;

    public GitCliService(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    public Path ensureClone(CodeRepositoryEntity repository) {
        Long lockKey = repository.getId() == null ? -1L : repository.getId();
        Object lock = repositoryLocks.computeIfAbsent(lockKey, key -> new Object());
        synchronized (lock) {
            return ensureCloneLocked(repository);
        }
    }

    private Path ensureCloneLocked(CodeRepositoryEntity repository) {
        try {
            Files.createDirectories(workRoot);
            Path localPath = workRoot.resolve("repo-" + repository.getId()).toAbsolutePath();
            if (Files.exists(localPath.resolve(".git"))) {
                run(localPath, gitCommand(repository, "fetch", "--all", "--prune"));
                run(localPath, gitCommand(repository, "checkout", repository.getDefaultBranch()));
                run(localPath, gitCommand(repository, "pull", "--ff-only", "origin", repository.getDefaultBranch()));
                return localPath;
            }
            run(workRoot.toAbsolutePath(), gitCommand(repository, "clone", repository.getRepoUrl(), localPath.toString()));
            run(localPath, gitCommand(repository, "checkout", repository.getDefaultBranch()));
            return localPath;
        } catch (IOException ex) {
            throw new BusinessException(6001, "准备仓库目录失败");
        }
    }

    public List<CommitResponse> listCommits(CodeRepositoryEntity repository, int limit) {
        Path localPath = ensureClone(repository);
        String output = run(localPath, "git", "log", "--max-count=" + limit,
                "--pretty=format:%H%x1f%P%x1f%an%x1f%ae%x1f%ct%x1f%s",
                repository.getDefaultBranch());
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
        Path localPath = ensureClone(repository);
        String base = baseCommitId;
        if (base == null || base.isBlank()) {
            base = resolveParentOrEmptyTree(localPath, commitId);
        }
        String rawDiff = run(localPath, "git", "diff", "--find-renames", base, commitId);
        List<DiffFileResponse> files = parseFiles(rawDiff);
        return new CommitDiffResponse(commitId, base, files, rawDiff);
    }

    private String resolveParentOrEmptyTree(Path localPath, String commitId) {
        String parents = run(localPath, "git", "rev-list", "--parents", "-n", "1", commitId).trim();
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

    private String[] gitCommand(CodeRepositoryEntity repository, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        addSafeDirectoryOptions(command, repository.getRepoUrl());
        String token = cryptoService.decrypt(repository.getAccessTokenCiphertext());
        if (shouldUseToken(repository.getRepoUrl(), token)) {
            command.add("-c");
            command.add("http.extraHeader=Authorization: Basic " + basicAuth(repository, token));
        }
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

    private boolean shouldUseToken(String repoUrl, String token) {
        return token != null
                && !token.isBlank()
                && repoUrl != null
                && repoUrl.toLowerCase().startsWith("http");
    }

    private String basicAuth(CodeRepositoryEntity repository, String token) {
        String username = switch ((repository.getProvider() == null ? "" : repository.getProvider()).toUpperCase()) {
            case "GITLAB", "GITEE" -> "oauth2";
            default -> "x-access-token";
        };
        return Base64.getEncoder().encodeToString((username + ":" + token).getBytes(StandardCharsets.UTF_8));
    }

    private String run(Path directory, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
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
        }
    }
}
