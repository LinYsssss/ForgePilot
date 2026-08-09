package com.example.codereview.git;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.CryptoService;
import com.example.codereview.repo.CodeRepositoryEntity;
import com.example.codereview.repo.RepositoryDtos.CommitDiffResponse;
import com.example.codereview.repo.RepositoryDtos.CommitResponse;
import com.example.codereview.repo.RepositoryDtos.DiffFileResponse;
import java.io.IOException;
import java.io.InputStream;
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

    private static final long COMMAND_TIMEOUT_SECONDS = 60;
    /** 抽干线程的收尾等待:进程已退出或被杀,剩余管道内容应当很快读完。 */
    private static final long DRAIN_JOIN_MS = 5000;
    /** 单次命令保留的输出上限,超出后继续读但丢弃,避免超大 diff 撑爆堆。 */
    private static final int MAX_OUTPUT_CHARS = 4_000_000;
    /** 进入异常消息的输出上限。 */
    private static final int MAX_ERROR_CHARS = 2000;

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
        String token = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(gitCommand(repository, args))
                    .directory(directory.toFile())
                    .redirectErrorStream(true);
            builder.environment().put("GIT_ALLOW_PROTOCOL", repository.getRepoUrl().toLowerCase().startsWith("http")
                    ? "http:https"
                    : "http:https:file");
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            token = cryptoService.decrypt(repository.getAccessTokenCiphertext());
            if (shouldUseToken(repository.getRepoUrl(), token)) {
                AskPassConfig askPass = createAskPass();
                askPassScript = askPass.scriptPath();
                builder.environment().put("GIT_ASKPASS", askPass.command());
                builder.environment().put("REPOSAGE_GIT_USERNAME", askPassUsername(repository));
                builder.environment().put("REPOSAGE_GIT_PASSWORD", token);
            }
            Process process = builder.start();
            // 必须在 waitFor 之前就开始消费管道。git 写满管道缓冲区(Linux 约 64KB)后会阻塞在写,
            // 而我们阻塞在 waitFor —— 双方互等,大仓库的 log/diff 必然死锁到超时被杀。
            OutputDrain drain = OutputDrain.start(process.getInputStream());
            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                drain.await(DRAIN_JOIN_MS);
                throw new BusinessException(6001, "Git 命令执行超时");
            }
            String output = drain.await(DRAIN_JOIN_MS);
            if (process.exitValue() != 0) {
                throw new BusinessException(6001, "Git 命令执行失败: " + sanitize(output, token, askPassScript));
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

    /**
     * git 的失败输出可能回显带凭据的远端地址或 askpass 脚本路径,这些都会进到
     * {@link BusinessException} 的 message 并最终出现在 API 响应与日志里,必须先脱敏。
     */
    static String sanitize(String output, String token, Path askPassScript) {
        if (output == null || output.isBlank()) {
            return "(无输出)";
        }
        String cleaned = output;
        if (token != null && !token.isBlank()) {
            cleaned = cleaned.replace(token, "***");
        }
        if (askPassScript != null) {
            cleaned = cleaned.replace(askPassScript.toString(), "***");
        }
        // 形如 https://user:secret@host/... 的内联凭据
        cleaned = cleaned.replaceAll("(?i)(https?://)[^/@\\s]*:[^/@\\s]*@", "$1***@");
        cleaned = cleaned.trim();
        return cleaned.length() <= MAX_ERROR_CHARS ? cleaned : cleaned.substring(0, MAX_ERROR_CHARS) + "…(已截断)";
    }

    /**
     * 后台线程限量抽干子进程输出。限量是为了让一次异常巨大的 diff 不至于把堆吃光——
     * 超过上限后继续读并丢弃,这样子进程不会因为没人读而卡住。
     */
    private static final class OutputDrain {

        private final Thread thread;
        private final StringBuilder buffer = new StringBuilder();
        private volatile boolean truncated;

        private OutputDrain(InputStream stream) {
            this.thread = new Thread(() -> {
                byte[] chunk = new byte[8192];
                try (InputStream in = stream) {
                    int read;
                    while ((read = in.read(chunk)) != -1) {
                        synchronized (buffer) {
                            int room = MAX_OUTPUT_CHARS - buffer.length();
                            if (room > 0) {
                                buffer.append(new String(chunk, 0, Math.min(read, room), StandardCharsets.UTF_8));
                            } else {
                                truncated = true;
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // 进程被强杀时管道会断开,已读到的内容仍然可用。
                }
            }, "git-output-drain");
            this.thread.setDaemon(true);
        }

        static OutputDrain start(InputStream stream) {
            OutputDrain drain = new OutputDrain(stream);
            drain.thread.start();
            return drain;
        }

        String await(long millis) throws InterruptedException {
            thread.join(millis);
            synchronized (buffer) {
                return truncated ? buffer + "\n…(输出过长已截断)" : buffer.toString();
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
