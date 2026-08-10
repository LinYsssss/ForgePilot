package com.example.codereview.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.CryptoService;
import com.example.codereview.repo.CodeRepositoryEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class GitCliServiceProcessTest {

    private final CryptoService cryptoService = mock(CryptoService.class);

    private CodeRepositoryEntity localRepo(String path, long id) {
        CodeRepositoryEntity repo = mock(CodeRepositoryEntity.class);
        when(repo.getId()).thenReturn(id);
        when(repo.getRepoUrl()).thenReturn(path);
        when(repo.getDefaultBranch()).thenReturn("main");
        when(repo.getAccessTokenCiphertext()).thenReturn(null);
        return repo;
    }

    @Test
    void sanitizeRedactsTokenInlineCredentialsAndAskpassPath() {
        String output = "fatal: unable to access 'https://x-access-token:ghp_supersecret@github.com/a/b.git'"
                + " askpass=/tmp/.work/repos/git-askpass-123.sh token=ghp_supersecret";

        String cleaned = GitCommandRunner.sanitize(output, "ghp_supersecret",
                Path.of("/tmp/.work/repos/git-askpass-123.sh"));

        assertThat(cleaned).doesNotContain("ghp_supersecret");
        assertThat(cleaned).doesNotContain("git-askpass-123.sh");
        assertThat(cleaned).contains("***");
        assertThat(cleaned).contains("unable to access");
    }

    @Test
    void sanitizeTruncatesAndHandlesEmptyOutput() {
        assertThat(GitCommandRunner.sanitize("   ", "t", null)).isEqualTo("(无输出)");
        assertThat(GitCommandRunner.sanitize(null, "t", null)).isEqualTo("(无输出)");

        String huge = "x".repeat(5000);
        String cleaned = GitCommandRunner.sanitize(huge, null, null);
        assertThat(cleaned).hasSizeLessThan(huge.length());
        assertThat(cleaned).endsWith("(已截断)");
    }

    /**
     * 回归:此前实现先 waitFor(60s) 再 readAllBytes,git 写满管道缓冲区(Linux 约 64KB)
     * 就会阻塞,双方互等直到超时被杀。这里让 git 产出远超缓冲区的输出,验证能正常返回。
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void largeOutputDoesNotDeadlock(@TempDir Path tempDir) throws Exception {
        Path repo = tempDir.resolve("big");
        Files.createDirectories(repo);
        run(repo, "git", "init", "-q");
        run(repo, "git", "config", "user.email", "t@example.com");
        run(repo, "git", "config", "user.name", "tester");
        // 单文件 ~2MB,远超 64KB 管道缓冲区
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 40000; i++) {
            content.append("line ").append(i).append(" ................................................\n");
        }
        Files.writeString(repo.resolve("big.txt"), content);
        run(repo, "git", "add", ".");
        run(repo, "git", "commit", "-qm", "big");
        run(repo, "git", "branch", "-M", "main");

        GitCliService service = new GitCliService(cryptoService, true);
        CodeRepositoryEntity entity = localRepo(repo.toString(), 90001L);

        // 必须在超时(60s)之前完成;死锁时这里会挂满 60s 再抛超时
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            String out = service.diff(entity, "HEAD", null).rawDiff();
            assertThat(out.length()).isGreaterThan(100_000);
        });
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void failedCommandSurfacesSanitizedMessage(@TempDir Path tempDir) throws Exception {
        Path repo = tempDir.resolve("empty");
        Files.createDirectories(repo);
        run(repo, "git", "init", "-q");
        run(repo, "git", "config", "user.email", "t@example.com");
        run(repo, "git", "config", "user.name", "tester");
        Files.writeString(repo.resolve("a.txt"), "hello\n");
        run(repo, "git", "add", ".");
        run(repo, "git", "commit", "-qm", "init");
        run(repo, "git", "branch", "-M", "main");

        GitCliService service = new GitCliService(cryptoService, true);
        CodeRepositoryEntity entity = localRepo(repo.toString(), 90002L);

        // 引用不存在 → git 非零退出 → 应抛出脱敏后的失败信息
        assertThatThrownBy(() -> service.diff(entity, "0000000000000000000000000000000000000000", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Git 命令执行失败");
    }

    private void run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor();
    }
}
