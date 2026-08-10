package com.example.codereview.git;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.CryptoService;
import com.example.codereview.repo.CodeRepositoryEntity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * git 子进程执行边界:命令组装(safe.directory)、凭据注入(askpass)、超时控制、
 * 限量抽干与失败输出脱敏。对外只有 {@link #run(Path, CodeRepositoryEntity, String...)}
 * 一个入口;git 语义(克隆生命周期、归档打包、log/diff 解析)不在此层,见
 * {@link GitCliService}。非 Spring Bean,由 GitCliService 构造持有。
 */
final class GitCommandRunner {

    private static final long COMMAND_TIMEOUT_SECONDS = 60;
    /** 抽干线程的收尾等待:进程已退出或被杀,剩余管道内容应当很快读完。 */
    private static final long DRAIN_JOIN_MS = 5000;
    /** 单次命令保留的输出上限,超出后继续读但丢弃,避免超大 diff 撑爆堆。 */
    private static final int MAX_OUTPUT_CHARS = 4_000_000;
    /** 进入异常消息的输出上限。 */
    private static final int MAX_ERROR_CHARS = 2000;

    private final CryptoService cryptoService;
    private final Path workRoot;

    GitCommandRunner(CryptoService cryptoService, Path workRoot) {
        this.cryptoService = cryptoService;
        this.workRoot = workRoot;
    }

    String run(Path directory, CodeRepositoryEntity repository, String... args) {
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
