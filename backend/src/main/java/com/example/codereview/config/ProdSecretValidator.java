package com.example.codereview.config;

import com.example.codereview.common.PinnedImageDigests;
import jakarta.annotation.PostConstruct;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class ProdSecretValidator {

    private static final Logger log = LoggerFactory.getLogger(ProdSecretValidator.class);

    private static final int MIN_LENGTH = 16;
    /** 种子管理员是平台唯一的初始 ADMIN,弱口令等于把最高权限挂在公网上。 */
    private static final int MIN_SEED_PASSWORD_LENGTH = 12;
    /** 会话 TTL 上限 7 天:令牌一旦泄露,窗口不应无限长。 */
    private static final long MAX_TOKEN_TTL_SECONDS = 604800;
    private static final Set<String> FORBIDDEN = Set.of(
            "dev-secret-change-me",
            "dev-token-encryption-key-change-me",
            "change-me",
            "change-me-to-a-long-random-string",
            "change-me-to-another-long-random-string",
            "guest",
            "replace-with-your-mimo-key"
    );
    /** 常见弱口令,长度达标也不能用。 */
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "administrator", "password1234", "admin123456", "123456789012", "qwerty123456"
    );

    private final String tokenSecret;
    private final String tokenEncryptKey;
    private final String dbPassword;
    private final String rabbitPassword;
    private final String llmApiKey;
    private final String sandboxSigningSecret;
    private final String sandboxToolImage;
    private final String seedUsername;
    private final String seedPassword;
    private final boolean authCookieSecure;
    private final long tokenTtlSeconds;
    private final boolean gitAllowLocalPath;
    private final boolean scmAllowInsecureLocalhost;

    public ProdSecretValidator(
            @Value("${app.security.token-secret}") String tokenSecret,
            @Value("${app.security.token-encrypt-key}") String tokenEncryptKey,
            @Value("${spring.datasource.password:}") String dbPassword,
            @Value("${spring.rabbitmq.password:}") String rabbitPassword,
            @Value("${app.ai.api-key:}") String llmApiKey,
            @Value("${app.sandbox.signing-secret:}") String sandboxSigningSecret,
            @Value("${app.sandbox.tool-image:}") String sandboxToolImage,
            @Value("${app.security.seed-admin.username:}") String seedUsername,
            @Value("${app.security.seed-admin.password:}") String seedPassword,
            @Value("${app.security.auth-cookie-secure:false}") boolean authCookieSecure,
            @Value("${app.security.token-ttl-seconds:86400}") long tokenTtlSeconds,
            @Value("${app.git.allow-local-path:false}") boolean gitAllowLocalPath,
            @Value("${app.scm.allow-insecure-localhost:false}") boolean scmAllowInsecureLocalhost
    ) {
        this.tokenSecret = tokenSecret;
        this.tokenEncryptKey = tokenEncryptKey;
        this.dbPassword = dbPassword;
        this.rabbitPassword = rabbitPassword;
        this.llmApiKey = llmApiKey;
        this.sandboxSigningSecret = sandboxSigningSecret;
        this.sandboxToolImage = sandboxToolImage;
        this.seedUsername = seedUsername;
        this.seedPassword = seedPassword;
        this.authCookieSecure = authCookieSecure;
        this.tokenTtlSeconds = tokenTtlSeconds;
        this.gitAllowLocalPath = gitAllowLocalPath;
        this.scmAllowInsecureLocalhost = scmAllowInsecureLocalhost;
    }

    @PostConstruct
    void validate() {
        check("JWT_SECRET (app.security.token-secret)", tokenSecret);
        check("TOKEN_ENCRYPT_KEY (app.security.token-encrypt-key)", tokenEncryptKey);
        check("DB_PASSWORD (spring.datasource.password)", dbPassword);
        check("RABBITMQ_PASSWORD (spring.rabbitmq.password)", rabbitPassword);
        check("SANDBOX_SIGNING_SECRET (app.sandbox.signing-secret)", sandboxSigningSecret);
        checkSandboxToolImage();
        checkOptional("LLM_API_KEY (app.ai.api-key)", llmApiKey);
        checkSeedAdmin();
        checkCookieAndTtl();
        checkOutboundLoopbackSwitches();
    }

    /**
     * 工具镜像缺失时网关只会软失败:返回 ENVIRONMENT_INCOMPLETE 而 /actuator/health 照常 UP,
     * 缺口在健康检查里完全不可见。生产环境把它变成启动期快速失败;格式校验与评测语料的
     * digest 固定要求同源({@link PinnedImageDigests}),但这里要求完整 64 位真实 digest。
     * dev/mock profile 不受影响 —— 本校验器仅在 prod profile 装配。
     */
    private void checkSandboxToolImage() {
        String name = "SANDBOX_TOOL_IMAGE (app.sandbox.tool-image)";
        if (sandboxToolImage == null || sandboxToolImage.isBlank()) {
            throw new IllegalStateException("生产环境必须配置 " + name
                    + "，否则沙箱工具调用会在运行期静默降级（ENVIRONMENT_INCOMPLETE）且健康检查不可见");
        }
        if (!PinnedImageDigests.isStrictlyPinned(sandboxToolImage.trim())) {
            throw new IllegalStateException("生产环境 " + name
                    + " 必须按完整 digest 固定，格式 <镜像>@sha256:<64位小写hex>，当前值不符合");
        }
    }

    /**
     * 这两个开关都属于联调用途,生产环境的处置不同:
     *
     * <ul>
     *   <li>{@code app.scm.allow-insecure-localhost} 会让回写地址允许明文 HTTP 且指向本机,
     *       没有任何正当的生产用途,直接启动失败;</li>
     *   <li>{@code app.git.allow-local-path} 放行的是**文件系统路径**形式的演示仓库,
     *       单机演示确实会用到,因此不阻断启动,但必须在日志里留痕——它允许把服务器上
     *       任意 git 仓库绑成项目。(其回环 HTTP 出站的那一半能力已在 GitInputValidator 里
     *       与本开关解耦,任何模式下都不放行。)</li>
     * </ul>
     */
    private void checkOutboundLoopbackSwitches() {
        if (scmAllowInsecureLocalhost) {
            throw new IllegalStateException(
                    "生产环境 SCM_ALLOW_INSECURE_LOCALHOST (app.scm.allow-insecure-localhost) 必须为 false，"
                            + "否则回写地址可指向本机且允许明文 HTTP");
        }
        if (gitAllowLocalPath) {
            log.warn("生产环境开启了 GIT_ALLOW_LOCAL_PATH (app.git.allow-local-path)："
                    + "本机文件系统上的任意 git 仓库都可被绑定为项目，仅应在单机演示时临时开启");
        }
    }

    /**
     * 种子管理员只在账号不存在时创建,但配置一旦留在 .env 里就会在每次全新部署时生效,
     * 因此弱口令必须在启动期拦下,而不是等到它已经建出一个公网可登录的 ADMIN。
     */
    private void checkSeedAdmin() {
        if (seedUsername == null || seedUsername.isBlank()) {
            return; // 未启用种子账号
        }
        String name = "SEED_ADMIN_PASSWORD (app.security.seed-admin.password)";
        if (seedPassword == null || seedPassword.isBlank()) {
            throw new IllegalStateException("生产环境配置了 SEED_ADMIN_USERNAME 就必须同时配置 " + name);
        }
        String trimmed = seedPassword.trim();
        if (trimmed.length() < MIN_SEED_PASSWORD_LENGTH) {
            throw new IllegalStateException("生产环境 " + name + " 太短，至少需要 "
                    + MIN_SEED_PASSWORD_LENGTH + " 个字符（当前 " + trimmed.length() + "）");
        }
        if (FORBIDDEN.contains(trimmed) || WEAK_PASSWORDS.contains(trimmed.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("生产环境 " + name + " 是常见弱口令，请改成强随机值");
        }
        if (trimmed.equalsIgnoreCase(seedUsername.trim())) {
            throw new IllegalStateException("生产环境 " + name + " 不能与用户名相同");
        }
    }

    private void checkCookieAndTtl() {
        if (!authCookieSecure) {
            throw new IllegalStateException(
                    "生产环境 AUTH_COOKIE_SECURE 必须为 true，否则认证 Cookie 会在明文 HTTP 上被回传");
        }
        if (tokenTtlSeconds <= 0) {
            throw new IllegalStateException("生产环境 TOKEN_TTL_SECONDS 必须为正数");
        }
        if (tokenTtlSeconds > MAX_TOKEN_TTL_SECONDS) {
            throw new IllegalStateException("生产环境 TOKEN_TTL_SECONDS 不得超过 "
                    + MAX_TOKEN_TTL_SECONDS + " 秒（7 天），当前 " + tokenTtlSeconds);
        }
    }

    private void check(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("生产环境必须配置 " + name + "，当前为空");
        }
        if (value.trim().length() < MIN_LENGTH) {
            throw new IllegalStateException("生产环境 " + name + " 太短，至少需要 " + MIN_LENGTH + " 个字符");
        }
        if (FORBIDDEN.contains(value.trim())) {
            throw new IllegalStateException("生产环境 " + name + " 仍是默认占位值，请改成强随机值");
        }
    }

    private void checkOptional(String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (FORBIDDEN.contains(value.trim())) {
            throw new IllegalStateException("生产环境 " + name + " 仍是默认占位值，请改成真实密钥");
        }
    }
}
