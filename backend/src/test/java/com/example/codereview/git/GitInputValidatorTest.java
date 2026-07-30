package com.example.codereview.git;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.codereview.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitInputValidatorTest {

    private void assertRepoUrlRejected(String repoUrl, boolean allowLocalPath) {
        assertThatThrownBy(() -> GitInputValidator.requireSafeRepoUrl(repoUrl, allowLocalPath))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 演示开关放行的是文件系统路径,不应该顺带把回环 HTTP 变成合法的出站目标:
     * 否则「打开演示」等于「打开一条 SSRF 通道」。
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8080/actuator/env",
            "http://localhost:5432/x.git",
            "https://[::1]/x.git",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.5/x.git",
    })
    void localPathSwitchDoesNotOpenLoopbackOrPrivateHttpTargets(String repoUrl) {
        assertRepoUrlRejected(repoUrl, false);
        assertRepoUrlRejected(repoUrl, true);
    }

    @Test
    void allowsFilesystemDemoRepoOnlyWhenSwitchIsOn() {
        assertThatCode(() -> GitInputValidator.requireSafeRepoUrl("/app/demo-repos/mall-order-service", true))
                .doesNotThrowAnyException();
        assertRepoUrlRejected("/app/demo-repos/mall-order-service", false);
    }

    @Test
    void rejectsSshScpLikeAndOptionLookalikeUrls() {
        assertRepoUrlRejected("git@github.com:acme/repo.git", true);
        assertRepoUrlRejected("ssh://git@github.com/acme/repo.git", true);
        assertRepoUrlRejected("--upload-pack=touch /tmp/pwn", true);
        assertRepoUrlRejected("https://example.com/a.git\nrm -rf /", true);
        assertRepoUrlRejected("", true);
    }

    @Test
    void allowsPublicHttpsRepositories() {
        assertThatCode(() -> GitInputValidator.requireSafeRepoUrl("https://github.com/octocat/Hello-World.git", false))
                .doesNotThrowAnyException();
    }

    @Test
    void refsRejectTraversalAndOptionInjection() {
        assertThatCode(() -> GitInputValidator.requireSafeRef("feature/login", "分支")).doesNotThrowAnyException();
        assertThatThrownBy(() -> GitInputValidator.requireSafeRef("../etc/passwd", "分支"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> GitInputValidator.requireSafeRef("--upload-pack=x", "分支"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> GitInputValidator.requireSafeRef("a b", "分支"))
                .isInstanceOf(BusinessException.class);
    }
}
