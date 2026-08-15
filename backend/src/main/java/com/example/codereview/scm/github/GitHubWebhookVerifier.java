package com.example.codereview.scm.github;

import com.example.codereview.webhook.WebhookSignatures;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

/**
 * 用 {@code X-Hub-Signature-256} 的 HMAC-SHA256 头校验一次 GitHub webhook 投递。
 *
 * <p>比遗留演示路径更严:SCM installation 一定带 webhook 密钥,因此「缺密钥」或
 * 「签名缺失/格式非法」都是硬失败,绝不跳过。HMAC 针对控制器捕获的**原始请求字节**计算——
 * 绝不针对重新序列化后的 JSON——比较走常量时间。复用 {@link WebhookSignatures} 里的共用 HMAC 原语。
 */
@Component
public class GitHubWebhookVerifier {

    public boolean verify(byte[] body, String signatureHeader, String secret) {
        if (secret == null || secret.isBlank()) {
            return false;
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        String expected = "sha256=" + WebhookSignatures.hmacSha256Hex(
                body == null ? new byte[0] : body, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));
    }
}
