package com.example.codereview.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 校验器只在 prod profile 装配,这里直接构造并调用 validate(),用来证明
 * “生产环境带着示例/弱配置会启动失败”，而不是靠人工检查 .env。
 */
class ProdSecretValidatorTest {

    private static final String STRONG = "a-sufficiently-long-random-value-0123456789";
    private static final String PINNED_IMAGE = "reposage/sandbox-tools@sha256:" + "0".repeat(64);

    private ProdSecretValidator validator(String sandboxSecret, String seedUser, String seedPassword,
                                          boolean cookieSecure, long ttlSeconds) {
        return new ProdSecretValidator(STRONG, STRONG, STRONG, STRONG, "",
                sandboxSecret, PINNED_IMAGE, seedUser, seedPassword, cookieSecure, ttlSeconds, false, false);
    }

    private ProdSecretValidator withToolImage(String toolImage) {
        return new ProdSecretValidator(STRONG, STRONG, STRONG, STRONG, "",
                STRONG, toolImage, "", "", true, 86400, false, false);
    }

    private ProdSecretValidator valid() {
        return validator(STRONG, "admin", "a-strong-seed-password", true, 86400);
    }

    @Test
    void acceptsFullyHardenedConfiguration() {
        assertThatCode(() -> valid().validate()).doesNotThrowAnyException();
    }

    @Test
    void rejectsPlaceholderAndShortCoreSecrets() {
        assertThatThrownBy(() -> new ProdSecretValidator("dev-secret-change-me", STRONG, STRONG, STRONG, "",
                STRONG, PINNED_IMAGE, "", "", true, 86400, false, false).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");

        assertThatThrownBy(() -> new ProdSecretValidator(STRONG, "short", STRONG, STRONG, "",
                STRONG, PINNED_IMAGE, "", "", true, 86400, false, false).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOKEN_ENCRYPT_KEY");

        assertThatThrownBy(() -> new ProdSecretValidator(STRONG, STRONG, "guest", STRONG, "",
                STRONG, PINNED_IMAGE, "", "", true, 86400, false, false).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD");
    }

    @Test
    void requiresSandboxSigningSecret() {
        assertThatThrownBy(() -> validator("", "", "", true, 86400).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SANDBOX_SIGNING_SECRET");

        assertThatThrownBy(() -> validator("too-short", "", "", true, 86400).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SANDBOX_SIGNING_SECRET");
    }

    @Test
    void rejectsWeakSeedAdminPasswordButIgnoresDisabledSeeding() {
        // 未启用种子账号时不校验密码
        assertThatCode(() -> validator(STRONG, "", "", true, 86400).validate()).doesNotThrowAnyException();

        // 8 位口令(当前生产 .env 的情况)必须被拦下
        assertThatThrownBy(() -> validator(STRONG, "admin", "lys0428.", true, 86400).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SEED_ADMIN_PASSWORD");

        assertThatThrownBy(() -> validator(STRONG, "admin", "", true, 86400).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SEED_ADMIN_PASSWORD");

        assertThatThrownBy(() -> validator(STRONG, "admin", "password1234", true, 86400).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("弱口令");

        // 够长且不在弱口令表里,但与用户名相同 —— 仍必须拒绝
        assertThatThrownBy(() -> validator(STRONG, "reposage-operator", "reposage-operator", true, 86400).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能与用户名相同");
    }

    @Test
    void requiresSecureCookieAndSaneTokenTtl() {
        assertThatThrownBy(() -> validator(STRONG, "", "", false, 86400).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_COOKIE_SECURE");

        assertThatThrownBy(() -> validator(STRONG, "", "", true, 0).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOKEN_TTL_SECONDS");

        assertThatThrownBy(() -> validator(STRONG, "", "", true, 604801).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("7 天");
    }

    /**
     * SCM 联调开关放行「明文 HTTP + 本机」回写地址,留在生产就是一条带凭据的 SSRF 通道,必须启动失败。
     * 演示用的 GIT_ALLOW_LOCAL_PATH 只记警告不阻断启动(单机演示确实要用),因此仍应启动成功。
     */
    @Test
    void rejectsInsecureScmLocalhostButToleratesLocalDemoRepoSwitch() {
        assertThatThrownBy(() -> new ProdSecretValidator(STRONG, STRONG, STRONG, STRONG, "",
                STRONG, PINNED_IMAGE, "", "", true, 86400, false, true).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SCM_ALLOW_INSECURE_LOCALHOST");

        assertThatCode(() -> new ProdSecretValidator(STRONG, STRONG, STRONG, STRONG, "",
                STRONG, PINNED_IMAGE, "", "", true, 86400, true, false).validate())
                .doesNotThrowAnyException();
    }

    /**
     * 工具镜像缺失时网关只会软失败(返回 ENVIRONMENT_INCOMPLETE 而健康检查照常 UP),
     * 生产必须在启动期拦下;且必须按完整 64 位小写 hex 的真实 digest 固定,
     * tag、缩写 digest、大写 hex 都不算数(与评测语料的形状校验分档,见 PinnedImageDigests)。
     */
    @Test
    void requiresDigestPinnedSandboxToolImage() {
        assertThatThrownBy(() -> withToolImage("").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SANDBOX_TOOL_IMAGE");

        assertThatThrownBy(() -> withToolImage("reposage/sandbox-tools:1.2.3").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest");

        assertThatThrownBy(() -> withToolImage("reposage/sandbox-tools@sha256:abc123").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest");

        assertThatThrownBy(() -> withToolImage("reposage/sandbox-tools@sha256:" + "A".repeat(64)).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest");

        assertThatCode(() -> withToolImage(PINNED_IMAGE).validate()).doesNotThrowAnyException();
    }
}
