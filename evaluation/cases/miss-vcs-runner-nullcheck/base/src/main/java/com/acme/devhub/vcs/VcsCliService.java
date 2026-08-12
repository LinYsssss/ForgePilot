package com.acme.devhub.vcs;

import com.acme.devhub.common.SecretBox;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * git 命令行封装：镜像同步、变更清单、diff 导出。
 *
 * <p>同时承担子进程执行边界：命令组装（safe.directory）、凭据注入
 * （askpass）、超时控制、限量抽干与失败输出脱敏。
 */
public class VcsCliService {

    private static final long COMMAND_TIMEOUT_SECONDS = 60;
    /** 抽干线程收尾等待：进程退出后剩余管道内容应当很快读完。 */
    private static final long DRAIN_JOIN_MS = 5000;
    /** 单次命令保留的输出上限，超出继续读但丢弃，避免超大 diff 撑爆堆。 */
    private static final int MAX_OUTPUT_CHARS = 4_000_000;
    /** 进入异常消息的输出上限。 */
    private static final int MAX_ERROR_CHARS = 2000;

    private final SecretBox secretBox;
    private final Path workRoot;

    public VcsCliService(SecretBox secretBox, Path workRoot) {
        this.secretBox = secretBox;
        this.workRoot = workRoot;
    }

    /** 同步镜像仓库（不存在则克隆，存在则 fetch）。 */
    public void syncMirror(RepoHandle repo) {
        Path mirror = mirrorPath(repo);
        if (Files.isDirectory(mirror.resolve("objects"))) {
            run(mirror, repo, "fetch", "--prune", "origin");
        } else {
            run(workRoot, repo, "clone", "--mirror", repo.getRemoteUrl(), mirror.toString());
        }
    }

    /** 两个提交之间的变更文件清单。 */
    public List<String> listChangedFiles(RepoHandle repo, String baseSha, String headSha) {
        String output = run(mirrorPath(repo), repo,
                "diff", "--name-only", baseSha + ".." + headSha);
        List<String> files = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (!line.isBlank()) {
                files.add(line.trim());
            }
        }
        return files;
    }

    /** 导出两个提交之间的统一 diff 文本。 */
    public String exportDiff(RepoHandle repo, String baseSha, String headSha) {
        return run(mirrorPath(repo), repo,
                "diff", "--unified=3", baseSha + ".." + headSha);
    }

    private Path mirrorPath(RepoHandle repo) {
        return workRoot.resolve(repo.getMirrorDirName());
    }

    private String run(Path directory, RepoHandle repo, String... args) {
        Path askPassScript = null;
        String token = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(gitCommand(repo, args))
                    .directory(directory.toFile())
                    .redirectErrorStream(true);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            token = secretBox.open(repo.getAccessTokenCipher());
            if (shouldUseToken(repo.getRemoteUrl(), token)) {
                askPassScript = createAskPass();
                builder.environment().put("GIT_ASKPASS", askPassScript.toString());
                builder.environment().put("ACME_GIT_USERNAME", askPassUsername(repo));
                builder.environment().put("ACME_GIT_PASSWORD", token);
            }
            Process process = builder.start();
            // 必须在 waitFor 之前开始消费管道，否则大仓库 log/diff 会互等死锁。
            OutputDrain drain = OutputDrain.start(process.getInputStream());
            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                drain.await(DRAIN_JOIN_MS);
                throw new VcsOperationException("git 命令执行超时");
            }
            String output = drain.await(DRAIN_JOIN_MS);
            if (process.exitValue() != 0) {
                throw new VcsOperationException("git 命令执行失败: " + sanitize(output, token));
            }
            return output;
        } catch (IOException ex) {
            throw new VcsOperationException("无法执行 git 命令，请确认已安装 Git");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new VcsOperationException("git 命令被中断");
        } finally {
            if (askPassScript != null) {
                try {
                    Files.deleteIfExists(askPassScript);
                } catch (IOException ignored) {
                    // askpass 临时脚本尽力清理。
                }
            }
        }
    }

    private String[] gitCommand(RepoHandle repo, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        addTrustedDirectoryOptions(command, repo.getRemoteUrl());
        command.addAll(List.of(args));
        return command.toArray(String[]::new);
    }

    /**
     * 本地路径仓库需要显式 safe.directory；远程 URL（http/git@）跳过。
     * 工作区镜像仓库的 remoteUrl 可能为 null（仅本地构建的裸镜像）。
     */
    private void addTrustedDirectoryOptions(List<String> command, String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()
                || remoteUrl.toLowerCase().startsWith("http") || remoteUrl.startsWith("git@")) {
            return;
        }
        String normalized = remoteUrl.replace('\\', '/').replaceAll("/+$", "");
        command.add("-c");
        command.add("safe.directory=" + normalized);
    }

    private boolean shouldUseToken(String remoteUrl, String token) {
        return remoteUrl != null && remoteUrl.toLowerCase().startsWith("http")
                && token != null && !token.isBlank();
    }

    private String askPassUsername(RepoHandle repo) {
        return repo.getUsername() == null || repo.getUsername().isBlank()
                ? "token"
                : repo.getUsername();
    }

    private Path createAskPass() throws IOException {
        Path script = Files.createTempFile(workRoot, "askpass-", ".sh",
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
        String body = "#!/bin/sh\n"
                + "case \"$1\" in\n"
                + "  Username*) printf '%s' \"$ACME_GIT_USERNAME\" ;;\n"
                + "  *) printf '%s' \"$ACME_GIT_PASSWORD\" ;;\n"
                + "esac\n";
        Files.writeString(script, body, StandardCharsets.UTF_8);
        return script;
    }

    private String sanitize(String output, String token) {
        String result = output == null ? "" : output;
        if (token != null && !token.isBlank()) {
            result = result.replace(token, "***");
        }
        if (result.length() > MAX_ERROR_CHARS) {
            result = result.substring(0, MAX_ERROR_CHARS) + "...(截断)";
        }
        return result;
    }

    /** 后台线程抽干进程输出，超过上限继续读但丢弃。 */
    private static final class OutputDrain {

        private final Thread worker;
        private final StringBuilder buffer = new StringBuilder();

        private OutputDrain(InputStream stream) {
            this.worker = new Thread(() -> {
                byte[] chunk = new byte[8192];
                try (InputStream in = stream) {
                    int read;
                    while ((read = in.read(chunk)) >= 0) {
                        if (buffer.length() < MAX_OUTPUT_CHARS) {
                            buffer.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                        }
                    }
                } catch (IOException ignored) {
                    // 进程被杀时管道关闭属预期。
                }
            }, "vcs-output-drain");
            this.worker.setDaemon(true);
        }

        static OutputDrain start(InputStream stream) {
            OutputDrain drain = new OutputDrain(stream);
            drain.worker.start();
            return drain;
        }

        String await(long joinMillis) throws InterruptedException {
            worker.join(joinMillis);
            return buffer.toString();
        }
    }
}
