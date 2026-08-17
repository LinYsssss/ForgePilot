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
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * git 语义层:克隆生命周期(按仓库加锁)、工作副本管理、归档打包、log/diff 解析。
 * 子进程执行细节(命令组装、askpass 凭据注入、超时、抽干、脱敏)在
 * {@link GitCommandRunner},本类只经 {@link #run(Path, CodeRepositoryEntity, String...)} 委托。
 */
@Service
public class GitCliService {

    private final Path workRoot = Path.of(".work", "repos");
    private final ConcurrentMap<Long, Object> repositoryLocks = new ConcurrentHashMap<>();
    private final GitCommandRunner runner;
    private final boolean allowLocalRepoPath;

    public GitCliService(CryptoService cryptoService,
                         @Value("${app.git.allow-local-path:false}") boolean allowLocalRepoPath) {
        this.runner = new GitCommandRunner(cryptoService, workRoot);
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

    /**
     * 产出 Agent 取证用的工作区归档:head 提交的树 + 预置的 base→head diff
     * ({@code .reposage/review.diff})。Runner 的 git.diff 命令不跑 git,只读这个预置文件,
     * 因此 diff 必须在打包时就算好并塞进归档,否则取证步骤只会得到
     * "prepared diff is unavailable"。
     */
    public void archiveForReview(CodeRepositoryEntity repository, String baseSha, String headSha, Path output) {
        GitInputValidator.requireSafeRef(baseSha, "Base Commit");
        GitInputValidator.requireSafeRef(headSha, "Commit");
        Long lockKey = repository.getId() == null ? -1L : repository.getId();
        Object lock = repositoryLocks.computeIfAbsent(lockKey, key -> new Object());
        synchronized (lock) {
            Path localPath = ensureCloneLocked(repository);
            String diff = run(localPath, repository, "diff", "--find-renames", baseSha, headSha, "--");
            archiveLocked(localPath, repository, headSha, java.util.Map.of("review.diff", diff), output);
        }
    }

    /**
     * 产出补丁验证用的工作区归档:commit 的树 + 调用方提供的附加文件(如
     * {@code .reposage/candidate.patch})。extraFiles 的 key 是 {@code .reposage/} 下的裸文件名。
     */
    public void archiveWithFiles(CodeRepositoryEntity repository, String commitSha,
                                 java.util.Map<String, String> extraFiles, Path output) {
        GitInputValidator.requireSafeRef(commitSha, "Commit");
        Long lockKey = repository.getId() == null ? -1L : repository.getId();
        Object lock = repositoryLocks.computeIfAbsent(lockKey, key -> new Object());
        synchronized (lock) {
            Path localPath = ensureCloneLocked(repository);
            archiveLocked(localPath, repository, commitSha, extraFiles, output);
        }
    }

    /**
     * 在持有仓库锁的前提下执行 git archive。产物先写 {@code <output>.partial} 再原子改名,
     * 避免 Runner 从共享卷读到半个 tar。--prefix 的语义:对 --add-file 生效的是其左侧最近
     * 一次出现,对 tracked 文件生效的是最右侧一次 —— 因此附加文件落在 .reposage/ 下,
     * 仓库树保持在归档根(已实测验证)。
     */
    private void archiveLocked(Path localPath, CodeRepositoryEntity repository, String commitSha,
                               java.util.Map<String, String> extraFiles, Path output) {
        Path extrasDir = null;
        try {
            Path target = output.toAbsolutePath().normalize();
            Files.createDirectories(target.getParent());
            Path partial = target.resolveSibling(target.getFileName() + ".partial");
            List<String> args = new ArrayList<>(List.of("archive", "--format=tar", "-o", partial.toString()));
            if (extraFiles != null && !extraFiles.isEmpty()) {
                extrasDir = Files.createTempDirectory("reposage-archive-extra-");
                args.add("--prefix=.reposage/");
                for (var extra : extraFiles.entrySet()) {
                    // --add-file 在归档内的路径 = 最近一次 --prefix + 文件 basename,
                    // 所以临时文件必须与目标文件名同名,且 key 只能是裸文件名。
                    Path file = extrasDir.resolve(extra.getKey()).normalize();
                    if (!extrasDir.equals(file.getParent())) {
                        throw new IllegalArgumentException("archive extra file name must be a bare file name");
                    }
                    Files.writeString(file, extra.getValue() == null ? "" : extra.getValue(), StandardCharsets.UTF_8);
                    args.add("--add-file=" + file);
                }
                args.add("--prefix=");
            }
            args.add(commitSha);
            run(localPath, repository, args.toArray(String[]::new));
            Files.move(partial, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            // 盲错带因(run15/run17 教训同类):BusinessException 不承载 cause,IOException 的
            // 定界原因(磁盘满/权限/路径)只能进 message,否则步骤失败时无从定位现场。
            String detail = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? ex.getClass().getSimpleName()
                    : ex.getMessage();
            throw new BusinessException(6001, "写入工作区归档失败: " + detail);
        } finally {
            deleteQuietly(extrasDir);
        }
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录残留只浪费磁盘,不影响正确性。
                }
            });
        } catch (IOException ignored) {
            // 同上,尽力而为。
        }
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

    // 包级可见以便单测覆盖归一化规则(去空白/末尾斜杠/单个 .git 后缀)。
    String normalizeRemote(String url) {
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

    /**
     * 远端分支名列表(去 {@code origin/} 前缀,上限 200 条)。P3 需求-代码关联的分支提取器
     * 数据源:调用点与 {@link #listCommits} 同一交互式同步链路,失败语义也一致(抛业务异常)。
     */
    public List<String> listBranches(CodeRepositoryEntity repository) {
        Path localPath = ensureClone(repository);
        String output = run(localPath, repository, "branch", "-r", "--format=%(refname:short)");
        List<String> branches = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String name = line.trim();
            if (name.isEmpty() || name.contains("HEAD")) {
                continue;
            }
            if (name.startsWith("origin/")) {
                name = name.substring("origin/".length());
            }
            branches.add(name);
            if (branches.size() >= 200) {
                break;
            }
        }
        return branches;
    }

    public CommitDiffResponse diff(CodeRepositoryEntity repository, String commitId, String baseCommitId) {        GitInputValidator.requireSafeRef(commitId, "Commit");
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

    private String run(Path directory, CodeRepositoryEntity repository, String... args) {
        return runner.run(directory, repository, args);
    }
}
