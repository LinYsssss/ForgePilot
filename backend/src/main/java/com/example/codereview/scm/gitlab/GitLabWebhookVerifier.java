package com.example.codereview.scm.gitlab;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

/**
 * 用 {@code X-Gitlab-Token} 头与 installation 的 webhook 密钥比对,校验一次 GitLab webhook 投递。
 *
 * <p>GitLab 用的是共享密钥令牌而非 HMAC 签名,所以这里是直接相等比较——同样走常量时间,
 * 同样从严:缺密钥或缺令牌都是硬失败。
 */
@Component
public class GitLabWebhookVerifier {

    public boolean verify(String tokenHeader, String secret) {
        if (secret == null || secret.isBlank()) {
            return false;
        }
        if (tokenHeader == null) {
            return false;
        }
        return MessageDigest.isEqual(
                tokenHeader.getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8));
    }
}
