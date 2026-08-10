package com.example.codereview.characterization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.CryptoService;
import com.example.codereview.git.GitCliService;
import com.example.codereview.repo.CodeRepositoryEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * 特征测试(锁现状,design.md 特征测试策略):批B 拆分 GitCliService 的进程执行管道
 * (askpass 凭据注入/失败输出脱敏/临时脚本清理)之前,该凭据分支没有任何直接覆盖——
 * 它只在 http(s) 远端 + 已配置 token 时可达,而 SSRF 策略挡掉回环地址,离线测试无法
 * 架设真实 http 远端。本测试用 RFC 6761 保证不可解析的 .invalid 主机让 clone 快速失败,
 * 只锁当下可观察的行为,不判断其对错:
 *
 * <ol>
 *   <li>http 仓库 + 密文 token → 凭据服务被查询(凭据路径确实接通);</li>
 *   <li>失败以 BusinessException("Git 命令执行失败")浮出,消息不含明文 token;</li>
 *   <li>askpass 临时脚本失败后被清理(.work/repos 不新增 git-askpass-* 残留);</li>
 *   <li>全程不交互挂起(GIT_TERMINAL_PROMPT=0,30 秒内返回)。</li>
 * </ol>
 *
 * <p>先对未改动代码跑绿,重构(抽出 GitCommandRunner)后测试原样不动即行为不变。
 * 将来可被针对进程执行边界的正经单测替换。
 */
class GitCredentialFlowCharacterizationTest {

    private static final Path WORK_ROOT = Path.of(".work", "repos");

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void httpCloneWithTokenFailsSanitizedAndCleansUpAskpass() throws IOException {
        CryptoService cryptoService = mock(CryptoService.class);
        when(cryptoService.decrypt("cipher-token")).thenReturn("charac-secret-token");
        CodeRepositoryEntity repo = mock(CodeRepositoryEntity.class);
        when(repo.getId()).thenReturn(90003L);
        when(repo.getRepoUrl()).thenReturn("http://reposage-charac.invalid/acme/app.git");
        when(repo.getDefaultBranch()).thenReturn("main");
        when(repo.getProvider()).thenReturn("GITHUB");
        when(repo.getAccessTokenCiphertext()).thenReturn("cipher-token");
        GitCliService service = new GitCliService(cryptoService, false);
        Set<String> askpassBefore = askpassResidue();

        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                assertThatThrownBy(() -> service.ensureClone(repo))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("Git 命令执行失败")
                        .satisfies(thrown -> assertThat(thrown.getMessage())
                                .doesNotContain("charac-secret-token")));

        verify(cryptoService).decrypt("cipher-token");
        assertThat(askpassResidue()).isEqualTo(askpassBefore);
    }

    /** 快照式对比:.work/repos 可能带有其他测试/历史构建的残留,只断言本次不新增。 */
    private Set<String> askpassResidue() throws IOException {
        if (Files.notExists(WORK_ROOT)) {
            return Set.of();
        }
        try (Stream<Path> paths = Files.list(WORK_ROOT)) {
            return paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("git-askpass-"))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
