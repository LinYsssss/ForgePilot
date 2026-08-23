package com.forgepilot.scm;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * 对<em>原始请求字节</em>校验 provider 的认证信息。
 *
 * <p>HMAC 覆盖的是 provider 签名时的那一串确切字节。把投递解析成对象再重新
 * 序列化，会改变键序、空白、转义和数字格式，于是诚实的投递反而会校验失败——
 * 而一个通过「先规范化再校验」来“修好”这件事的实现，已经不再是在认证它随后
 * 据以行动的那些字节了。{@code WebhookSignatureVerifierTest} 用一个能解析出
 * 相同文档、但字节不同的请求体把这一点钉死。
 */
@Component
class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String GITHUB_PREFIX = "sha256=";
    private static final String GITLAB_PREFIX = "v1,";
    private static final String GITLAB_SECRET_PREFIX = "whsec_";
    private static final Duration GITLAB_MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final Clock clock;

    WebhookSignatureVerifier() {
        this(Clock.systemUTC());
    }

    WebhookSignatureVerifier(Clock clock) {
        this.clock = clock;
    }

    boolean matches(byte[] body, String secret, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith(GITHUB_PREFIX)) {
            return false;
        }
        byte[] expected = HexFormat.of().formatHex(hmac(secret.getBytes(StandardCharsets.UTF_8), body))
                .getBytes(StandardCharsets.UTF_8);
        byte[] provided = signatureHeader.substring(GITHUB_PREFIX.length())
                .toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        // 常数时间比较，且对长度安全：被截断的十六进制摘要只会判为不相等。
        return MessageDigest.isEqual(expected, provided);
    }

    /**
     * 当前版本的 GitLab 按 Standard Webhooks 签名；较老的安装则发送明文
     * 秘密 token。若 Standard 形式存在但校验不通过，绝不会降级到更弱的
     * 旧版形式。
     */
    boolean matchesGitLab(byte[] body, String secret, String signatureHeader,
            String messageId, String timestampHeader, String tokenHeader) {
        boolean hasStandardHeader = signatureHeader != null || messageId != null || timestampHeader != null;
        if (!hasStandardHeader) {
            return constantTimeTextEquals(secret, tokenHeader);
        }
        if (signatureHeader == null || messageId == null || timestampHeader == null
                || !secret.startsWith(GITLAB_SECRET_PREFIX)) {
            return false;
        }

        Instant signedAt;
        byte[] key;
        try {
            signedAt = Instant.ofEpochSecond(Long.parseLong(timestampHeader));
            key = Base64.getDecoder().decode(secret.substring(GITLAB_SECRET_PREFIX.length()));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        if (key.length == 0 || Duration.between(signedAt, clock.instant()).abs()
                .compareTo(GITLAB_MAX_CLOCK_SKEW) > 0) {
            return false;
        }

        byte[] prefix = (messageId + "." + timestampHeader + ".").getBytes(StandardCharsets.UTF_8);
        byte[] expected = (GITLAB_PREFIX + Base64.getEncoder().encodeToString(hmac(key, prefix, body)))
                .getBytes(StandardCharsets.UTF_8);
        for (String candidate : signatureHeader.trim().split("\\s+")) {
            if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private static boolean constantTimeTextEquals(String expected, String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            for (int index = 0; index < parts.length - 1; index++) {
                mac.update(parts[index]);
            }
            return mac.doFinal(parts[parts.length - 1]);
        } catch (GeneralSecurityException impossible) {
            // HmacSHA256 是每个 JDK 都必须提供的，而密钥永远不为空。
            throw new IllegalStateException(impossible);
        }
    }
}
