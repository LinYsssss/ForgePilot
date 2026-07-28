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

    private ProdSecretValidator validator(String sandboxSecret, String seedUser, String seedPassword,
                                          boolean cookieSecure, long ttlSeconds) {
        return new ProdSecretValidator(STRONG, STRONG, STRONG, STRONG, "",
                sandboxSecret, seedUser, seedPassword, cookieSecure, ttlSeconds);
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
                STRONG, "", "", true, 86400).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");

        assertThatThrownBy(() -> new ProdSecretValidator(STRONG, "short", STRONG, STRONG, "",
                STRONG, "", "", true, 86400).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOKEN_ENCRYPT_KEY");

        assertThatThrownBy(() -> new ProdSecretValidator(STRONG, STRONG, "guest", STRONG, "",
                STRONG, "", "", true, 86400).validate())
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
}
